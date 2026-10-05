# 核心代码地图（Code Map）

> 按六大主题整理：工具调用、检索、记忆机制、工作流、上下文、评估与测试。
> 每个主题给出职责、核心类（路径 + 关键方法）和调用链，用于快速定位代码。
> 所有路径省略前缀 `src/main/java/com/enterprise/iqk/`，测试省略 `src/test/java/com/enterprise/iqk/`。

## 模块总览

```
入口层    controller/（Chat、Pdf、React、DeepResearch、Workflow、Ingestion、Memory、Evaluation、AgentHarness...）
          │
生成链路  rag/RagAnswerService（简单版）   rag/HybridRagAnswerService（完整管线）
          │                                    │
          │            ┌───────────────────────┼────────────────────────┐
          │            ▼                       ▼                        ▼
检索     retrieval/（四路混合）        memory/（四层记忆注入）      llm/ModelRouter（模型分档）
          │
Agent    service/ReactAgentService ──► agent/harness/（动作执行 + 策略守卫）
          agent/workflow/（状态机引擎）  agent/research/（深度研究编排）
          │
基础     ingestion/（入库）  graph/（知识图谱）  security/（认证/限流/扫描）  evaluation/（评测）
```

---

## 一、工具调用（Tool Calling & Agent Harness）

**职责**：模型发起动作 → 策略守卫 → 分发执行 → 观测记录。两条通道：Spring AI `@Tool`（进程内）与自研 harness 动作体系（含 MCP 出站桥接）。

### 进程内工具（Spring AI @Tool）

| 类 | 职责 |
|---|---|
| `tools/CourseTools.java` | 课程领域工具：query_school / query_course / add_course_reservation；LambdaQueryWrapper 查询、tenant_id 过滤、排序列白名单（SFunction 映射防注入）、预约幂等打标 |
| `tools/SqlReadOnlyGuard.java` | query_database 的 SQL 只读守卫（纯函数）：剥注释/字面量后做单语句 + SELECT 白名单 + 写关键词整词黑名单（含改数 CTE/FOR SHARE）+ 表黑名单 + LIMIT 收敛；`extractTableNames` 供租户启发式复用 |
| `tools/DatabaseQueryTools.java` | query_database 执行端：守卫 → 租户过滤启发式（information_schema + 5 分钟 TTL 缓存，fail-open）→ 只读会话 + 超时 + 驱动级行数硬顶 + 单元格截断；tool.query.latency 埋点 |

### Agent Harness（自研动作体系，`agent/harness/`）

| 类 | 职责 |
|---|---|
| `ActionSchema.java` / `ActionSchemaRegistry.java` | 动作 schema 与注册表；构造器一次性注册 12 个动作（builtin 5 + mcp_call 1 + workspace 6）；`find()` Optional 查找，读时不可变拷贝 |
| `AgentHarnessService.java` | 动作执行入口 `execute(AgentAction)`：校验 schema → 策略守卫 → 分发 runtime → 记录观测 |
| `ActionPolicyGuard.java` / `ActionPolicyDecision.java` | 调用前策略判定（允许/拒绝/需受信确认） |
| `TrustedActionService.java` | 受信动作：高危操作（如写库）先出预览，确认后执行，token 清理 |
| `BuiltinToolRuntime.java` | 内置动作执行：query_school/query_course/add_course_reservation/rag_search/query_database（rag_search 内部调 `rag/HybridRagAnswerService` 四路混合，citations 映射回 `source=..., chunk=...` 文本、载荷键 query/answer/citations/evidence/weights 与旧单路一致；query_database 内部调 `tools/DatabaseQueryTools`，租户从 action.tenantId() 服务端注入） |
| `WorkspaceRuntime.java` | 工作区动作：文件读写/搜索/受限 shell，mvn/git/ripgrep 白名单，文件大小与搜索结果截断 |
| `McpToolAdapter.java` / `HttpMcpToolAdapter.java` / `McpToolRuntime.java` | MCP 出站桥接：手拼 JSON-RPC 2.0 `tools/call`，JDK HttpClient 直连；SSRF 防护（拒绝 RFC1918/回环/云元数据地址 + allowedHosts 白名单）、响应体 2 MiB 上限、瞬时故障退避重试（重试不过模型、不烧 token）。已接首个真实工具：天气（详见下方"MCP 工具调用全链路"） |
| `HarnessPayloadSanitizer.java` | 观测载荷消毒裁剪（防工具返回值撑爆上下文） |
| `HarnessEventRecorder.java` / `AgentObservation.java` | 执行留痕与观测结构 |
| `UnifiedDiffService.java` | 结构化 diff 输出（受信动作预览用） |

**调用链**：`ReactAgentService.executeAction()`（`service/ReactAgentService.java:319`）→ `AgentHarnessService.execute()` → 策略守卫 → 按 schema 分发到对应 Runtime → `AgentObservation` 回填 ReAct 轨迹。

### MCP 工具调用全链路（当前工具：天气查询）

**出站方向**：主应用 → 翻译壳容器 → Open-Meteo（免费天气 API，无需钥匙）。壳是独立单文件 Python 服务（仅标准库），按本项目的 JSON-RPC 桥接形状收发 `tools/call`——自研简版桥，不握手、不列工具，接新工具需按此形状写壳。

| 环节 | 代码位置 |
|---|---|
| ① 规划提示词（教模型何时调、参数怎么填） | 由 `agent/harness/PlannerActionCatalog` 从动作注册表生成：standard 引擎取整段 bullet 列表（`standardActionsBlock`）、workflow 引擎取 slash 行 + hint 说明行（`workflowActionsSection`）；带 `plannerHint` 的动作（mcp_call）自动附用法 `{"server":"weather","tool":"get_weather","arguments":{"city":"城市中文名"}}`，无 hint 只出裸动作名 |
| ② 决策解析白名单 | `agent/harness/PlannerActionCatalog.isPlannerAction()`：两引擎共用同一名单，从注册表派生（trustedOnly=false 的动作 + finish，与策略守卫"聊天循环只能执行非受信动作"口径一致）——白名单外动作强制归 finish |
| ③ schema 注册（mcp_call 非 trustedOnly） | `agent/harness/ActionSchemaRegistry.java`：required=server/tool/arguments、riskLevel=external、trustedOnly=false、plannerHint=天气用法（①②均由本表单一来源生成，受信边界见下） |
| ④ 统一入口 + 策略守卫 | `agent/harness/AgentHarnessService.execute()` → `ActionPolicyGuard.evaluate()`（六道检验：schema 存在 / disabled-actions 熔断 / 租户动作白名单 / 受信要求 / 必填字段 / schema 外未知字段） |
| ⑤ 运行时分发 | `agent/harness/McpToolRuntime.execute()`：从 actionInput 取 server/tool/arguments → 按 supports(server,tool) 选适配器 → 壳返回 status=error 即转错误观测，成功包成 `{"server","tool","result"}` 观测 |
| ⑥ HTTP 桥接 | `agent/harness/HttpMcpToolAdapter.execute()`：组 JSON-RPC `tools/call` → SSRF 复检（`isSafeBaseUrl`，拼 URI 前后再查一次）→ 按工具超时请求（瞬时故障本层线性退避重试）→ 2 MiB 上限 → 解析回执 |
| ⑦ 翻译壳（部署物） | `mcp-weather/mcp_weather.py`：`POST /mcp/tools/call` → Open-Meteo geocode（城市名→经纬度，中文可查）+ forecast（实况 + 当日温度/天气码中文化）→ 含 summary 的中文摘要；查无城市/上游故障回 `{"status":"error","message":...}`（HTTP 恒 200，避免被当网络故障重试）。`deploy/docker-compose.prod.yml` 的 `mcp-weather` 服务（python:3.12-slim，端口 127.0.0.1:9101 仅回环，脚本卷挂载免自定义镜像） |
| ⑧ 配置 | `application.yml` `app.agent-harness.mcp.servers.weather`：base-url 指容器名 `http://mcp-weather:9101`（本地无此容器时 supports() 判未注册、不影响其它功能）+ `allowed-hosts` 放行容器名（否则 SSRF 护栏拒一切私有地址）+ 工具超时 8s（覆盖壳内 geocode+forecast 串行） |

**完整调用链**：用户问天气 → 规划器输出 mcp_call JSON → ② 白名单放行 → ④ 守卫放行 → ⑤ McpToolRuntime 选适配器 → ⑥ HttpMcpToolAdapter 发 JSON-RPC → ⑦ 壳查 Open-Meteo → 观测回填 ReAct 轨迹 → 模型 finish 作答。standard 与 workflow 两条 ReAct 引擎均可用。

**受信边界**：mcp_call 与 workspace 写/壳动作不同——只读外部查询，风险由 SSRF 校验、allowed-hosts、2 MiB 上限、按工具超时兜底，故 trustedOnly=false、聊天循环直接调用；`TrustedActionService` 的 preview/execute 两段式流程依旧只收 trustedOnly 动作（对 mcp_call 报 "action does not require trusted runtime"，属设计使然）。运维熔断开关：`app.agent-harness.disabled-actions`、租户动作白名单。

**测试**：`agent/harness/HttpMcpToolAdapterTest`（SSRF/重试/上限）、`McpToolRuntimeTest`（分发与错误转换）、`ActionPolicyGuardTest`（mcp_call 无受信标记可调）、`HarnessEvaluationTest`（harness 全链）、`service/ReactDecisionParserTest`（mcp_call 解析放行）、`PlannerActionCatalogTest`（注册表 → 提示词/白名单生成）。

**新增动作只登记一处**：在 `ActionSchemaRegistry` 注册（必填/可选/敏感字段 + riskLevel + trustedOnly + plannerHint）后，①提示词与②白名单自动跟上；执行侧仍按工具逐个实现（builtin 加 `BuiltinToolRuntime` 分支、mcp 加 `application.yml` servers 配置 + 翻译壳、workspace 加 `WorkspaceRuntime` 分支）。

---

## 二、检索（Hybrid Retrieval & Ingestion）

**职责**：四路并行召回 → 加权融合 → 去重 → 证据判分 → 引用构建；另有文档入库（PDF/Word/Markdown）与知识图谱两路数据供给。

**链路分工（重要）**：主聊天两条 ReAct 引擎的 `rag_search` 与评测共用 `rag/HybridRagAnswerService`（**四路混合 + 证据判分 + 无关线兜底**：全路原始分低于 `rag.fallback-score-floor` 时向量放宽阈值重试一次，仍无过线文档则不调模型返回固定话术）；`rag/RagAnswerService`（向量单路 + 放宽阈值兜底）只剩 PDF 文档问答 `/ai/pdf/chat`；深度研究仍直用 `HybridRetrievalService`。前端轨迹的「检索召回路」色条按观测载荷 `weights` 实况绘制：四路链路回归一化后的配置权重（vector/keyword/graph/web 固定顺序），无 `weights` 的旧消息不画条。

### 混合检索（`retrieval/`）

| 类 | 职责 |
|---|---|
| `HybridRetrievalService.java` | 核心：`retrieve()` 四路并行（CompletableFuture + 专用线程池，`app.retrieval.pool-size` 默认 16，**须 ≤ DB 连接池 `DB_POOL_MAX_SIZE` 默认 20**——三路直连数据库）→ `applyWeight()` 加权（finalScore = retrievalScore × 权重，权重来自 `app.retrieval.weights.vector/keyword/graph/web` 配置、默认 0.40/0.25/0.20/0.15，**结果回带归一化后的实际权重**）→ `deduplicate()` 去重（内容前 200 字符规范化指纹，同指纹保留分高者）→ 按 finalScore 排序取 topK；单路**超时从任务真正开始执行起算**（排队不占预算，高并发排队不再集体假超时），到点中断运行线程**真取消**；队列有界（`app.retrieval.queue-capacity` 默认 64），池/队列打满提交被拒 → 立即降级并记 `saturated`；按路计数 `retrieval.source.requests{source,outcome=success/error/timeout/saturated}` + 排队时长 `retrieval.queue.wait{source}`，结果携带 `degradedSources`、整体 outcome 标 `degraded`/`degraded-empty`（局部故障不伪装成"知识库为空"），池活跃/队列数 gauge（retrieval.pool.active/queued）；web 路禁用（`app.web-search.enabled=false`）时短路不提交任务、不占槽；worker `catch (Exception)` 保证停机中断也完成 promise（join 不永挂） |
| `HybridWeights.java` | 权重值对象：默认档 DEFAULT（与 application.yml 默认一致）、预置 SEMANTIC/KEYWORD/BALANCED 档位，`normalize()` 归一化；运行时默认权重以 `app.retrieval.weights.*` 配置为准 |
| `VectorRetriever.java` / `KeywordRetriever.java` / `GraphRetriever.java` / `WebRetriever.java` | 四路各自实现；向量/关键词路均为租户级过滤 + 会话软作用域（chat_id 不硬过滤，同会话命中 +0.05 有界加分）；向量路支持显式阈值重载（`HybridRagAnswerService` 无关线兜底放宽重试用 accept-all 走它）；keyword 路在向量候选池（阈值 0、池 max(topK×4,40)）上做 CJK 2-gram 词法重排；web 默认关闭（`app.web-search.enabled`） |
| `ChatScope.java` | 会话软作用域：租户共享知识库，chat_id 只作有界加分不作硬边界（防跨会话割裂与临时 chatId 必空） |
| `LexicalMatcher.java` | 词面匹配共用工具：CJK 感知切词（中文段 2-gram、拉丁段整token）+ 查询召回分（分母=查询 token 数），keyword 路/线上重排/证据判分三处共用 |
| `RetrievalPreviewService.java` + `RetrievalPreviewItem/Result` | 知识库「试搜」：只走向量/关键词/图谱三条本地路返回原始命中（不调 LLM、不出答案），单路失败降级并记 `degradedSources` + `log.warn` 留痕，供 `GET /ingestion/search` |
| `web/WebSearchBackend.java` + `SearXNGBackend` / `BingSearchBackend` / `WebSearchProperties` | 外部搜索适配层（SearXNG 自托管 / Bing API） |
| `Reranker.java` / `IdentityReranker.java` | 重排接口与恒等实现 |
| `ScoredDocument.java` | 检索结果统一结构（docId/sourceType/title/content/retrievalScore/finalScore） |
| `EvidenceJudgeService.java` / `EvidenceItem.java` | 证据判分：相关性/权威性/时效性三维评分（时效度由入库 `created_at` 激活）；判分结果被消费——按综合分组织生成上下文、剔除低于 0.30 垃圾线的证据、截断 rerankTopK |
| `CitationService.java` / `CitationItem.java` | 引用构建与脚注格式化（`formatCitationFooter`） |

### 数据供给（`ingestion/`、`graph/`）

| 类 | 职责 |
|---|---|
| `ingestion/IngestionService.java` | 文档入库（PDF 走 PagePdfDocumentReader，doc/docx/md 走 Tika）：解析、分块、向量化、元数据（tenant_id/chat_id/job_id/file_name/source_type/chunk_index/created_at，时间戳激活证据判分时效度）；文档清单 `listDocumentsByTenant`（每 chat 取最新任务）与删除 `deleteDocumentByChat`（向量切片 → 磁盘文件 → 任务记录级联，任一步失败即中止） |
| `ingestion/IngestionWorker.java` + `queue/`（RabbitMq / RedisStream / Noop / db_polling） | 异步入队消费，多后端可切换 |
| `graph/GraphService.java` + `KgEntityRecord` / `KgFactRecord` / `KgRelationRecord` | 知识图谱读侧：实体/关系/事实查询供 GraphRetriever（中文文本适配）；写入侧 `graph/GraphExtractionService.java` 在文档入库完成后由 LLM 抽取实体/关系/事实入库，文档删除联动清理对应图谱数据 |
| `ingestion/DocumentGraphBackfillService.java` | 存量文档补图谱：重解析磁盘文档重建该 chat 的实体/关系/事实，`POST /ingestion/documents/{chatId}/graph/build` 触发 |
| `ingestion/DocumentContentService.java` + `domain/vo/DocumentContentVO` | 文档内容预览：找最近一次 SUCCEEDED 且磁盘文件还在的任务，复用包内可见 `IngestionService.parseAndSplit` 重解析取切片文本（只读、不写向量库），按入库顺序返回内容块（PDF 块带 `page_number` 页码），正文超 20 万字符截断并置 `truncated`；`GET /ingestion/documents/{chatId}/content`，前端「文档清单」点文件名打开抽屉 |
| `config/VectorStoreConfiguration.java` | pgvector VectorStore 装配（`OpenAiEmbeddingModel`） |

**调用链**：`HybridRagAnswerService.answer()` 第 1 步四路检索（`rag/HybridRagAnswerService.java:79`）→ 第 1.5 步无关线兜底（:99-119；全低于线时向量放宽重试 `relaxedVectorRetry()` :221）→ 第 2 步证据判分（:122）→ 第 3 步引用（:130）。

---

## 三、记忆机制（Four-Layer Memory）

**职责**："写入时机决定层级"：对话完成→short（24h）、工作流 DONE→task（30 天）、RAG 证据≥0.7→fact（永久）、异步画像提取→long（永久）。全部 best-effort，失败不阻塞主链路。

### 核心与存储（`memory/`）

| 类 | 职责 |
|---|---|
| `MemoryService.java` | 统一入口：`saveShortMemory/saveLongMemory/saveTaskMemory/saveFactMemory`（:27-49）、按层查询（:77-92）、`buildContext()` 召回快照（:99-124，5/10/5 条上限 + fact 复验 0.7 + USE 事件留痕）、`queryRecentTaskMemories()` 租户最近任务结论（深度研究拆题注入）、`cleanExpiredMemories()` 凌晨 3 点清理（:128）、事件读写 `emitEvent/getEvents`（:146-164） |
| `MemoryInjectionAdvisor.java` | advisor 注入：请求组装期把记忆快照插成 prompt 首部独立 SystemMessage —— user 消息保持原文，MessageChatMemoryAdvisor 存的仍是原始对话，会话历史不逐轮累积记忆段；调用方传 `memory.tenantId`+`memory.userId` 参数显式 opt-in，未传参/召回失败一律透传 |
| `MemoryItemRecord.java` | memory_item 表：type 区分四层、expiresAt 控生命周期、confidence、source 来源追溯 |
| `MemoryEventRecord.java` | memory_event 表：CREATE/UPDATE/DELETE/EXPIRE/HIT/USE 事件溯源 |

### 四个写入器 + 提取器（`memory/`）

| 类 | 写入时机 → 层级 |
|---|---|
| `ChatTurnMemoryRecorder.java` | 每轮对话完成 → short；Q/A 格式截断（200/400 字符）、空白规范化 |
| `TaskConclusionMemoryRecorder.java` | 工作流 DONE → task；"[类型] 目标：…\n结论：…" 格式（160/600 截断） |
| `RagFactMemoryRecorder.java` | RAG 证据置信度 ≥0.7 → fact；单次最多 3 条、单条失败不阻塞其余、source 格式 `rag:{sourceType}:{title}` |
| `MemoryExtractionService.java` | 对话完成异步 → economy 档模型判定 `{"isProfile":bool,"memory":"..."}` → long；独立 2 线程池、独立会话 ID（memory-extract:{chatId}）、规范化包含比对去重、解析失败默认不升级 |

### 接线点（三处）

| 位置 | 内容 |
|---|---|
| `controller/ChatController.java` | `trackedChatStream` 的 `doFinally`（ON_COMPLETE）→ `ChatTurnMemoryRecorder.recordTurn` + `MemoryExtractionService.submitAsync`；生成调用带 `memory.tenantId`/`memory.userId` advisor 参数（user 键 = 认证主体，匿名回落 chatId），记忆由 MemoryInjectionAdvisor 注入 |
| `agent/workflow/AgentWorkflowEngine.java` | `completeTask`（finalStatus==DONE）→ `TaskConclusionMemoryRecorder.recordConclusion`；FAILED 不写 |
| `rag/HybridRagAnswerService.java` | Step 2.5 事实沉淀（:127）+ Step 4.5 召回注入（:139-143）；`recallMemory()` 失败降级 null（:255-262） |

**API**：`controller/MemoryController.java` — `GET /ai/memory/query`（聚合查询）、`GET /ai/memory/{id}/events`（事件链，租户隔离）、`POST /ai/memory/save`（手动写入）。

---

## 四、工作流（Workflow State Machine & Orchestration）

**职责**：三层架构——引擎层（零业务智能）、编排层（剧本）、执行层（Agent）。复杂任务可观测、可审计、可回放。

### 引擎层（`agent/workflow/`）

| 类 | 职责 |
|---|---|
| `WorkflowState.java` | 状态枚举：CREATED→PLANNING→SEARCHING→RETRIEVING→JUDGING→REFLECTING→WRITING→DONE（另有 NEED_MORE_EVIDENCE/FAILED）；`canTransitionTo` 守卫合法转移 |
| `AgentWorkflowEngine.java` | 任务生命周期/状态管理/事件溯源：startStep/completeStep、状态落库、`completeTask` 触发任务结论记忆、`abandonTask` 守卫式收尾（SSE 断连/孤儿回收用，不覆盖已终态任务） |
| `AgentTaskRecord` / `AgentStepRecord` / `AgentEventRecord` + 各 Mapper | 任务/步骤/事件持久化（agent_task / agent_step / agent_event 表）；`findStaleTasks`/`failIfNotTerminal` 支撑孤儿回收 |
| `WorkflowReactAgentService.java` | ReAct 迭代与状态映射：`mapToWorkflowState(step)`（:1→SEARCHING、2→RETRIEVING、3→JUDGING、4→REFLECTING、其余→WRITING）；`stream()` 真流式（`stepFlux` 递归单步流，每步完成即发 trace 帧）+ 断连处理（doFinally 识别 CANCEL → abandonTask，任务不停在非终态） |
| `WorkflowTaskReclaimer.java` | 孤儿任务回收：@Scheduled 每 5 分钟把非终态超 30 分钟的任务守卫式置 FAILED（运行期兜底保险丝） |
| `WorkflowTaskStartupSweeper.java` | 启动清扫器（ApplicationRunner）：进程启动即把上一进程遗留的全部非终态任务守卫式收尾（批量循环扫到空批，上限 20 批），`agent.workflow.task.swept` 指标；run 绝不抛异常（ApplicationRunner 抛出会中止启动），故障只记日志放行启动 |
| `ReactPlannerFallbacks.java` | 规划器确定性降级（关键词路由预设动作）+ 动作白名单归一 |
| `controller/WorkflowController.java` | 工作流任务提交与状态查询 |

### 编排层（`agent/research/`）

| 类 | 职责 |
|---|---|
| `DeepResearchService.java` | 深度研究剧本：`createResearch()` 异步受理（同步落库 startTask → 提交专用池 `researchExecutor`（`app.research.worker-count` 3 / `queue-capacity` 20）→ 返回 202 形状 result{report=null, status=PLANNING}，队列满守卫式 abandonTask + 抛 `ResearchQueueFullException`（对外 429））；`executeResearch()` 后台执行拆题→检索→评证→反思→成稿 的状态转移与步骤编排；`@PreDestroy` shutdownNow + 限时等待，漏网任务由启动 sweep 收尾 |
| `ResearchPlannerAgent.java` | 拆题（LLM 规划，含防御性提取） |
| `ReportWriterAgent.java` | 成稿（LLM 汇总） |
| `ResearchTaskRequest.java` | 任务请求结构 |
| `controller/DeepResearchController.java` | 深度研究入口：POST /tasks 异步受理（202 + taskId，队列满 429）、GET /tasks/{id} 状态轮询、GET events 事件流、GET report 报告查询 |

### ReAct 循环（`service/`）

| 类 | 职责 |
|---|---|
| `ReactAgentService.java` | ReAct 主循环：`reason()` 规划（:234-264，动作白名单硬编码，含 Known memories 记忆段）→ `executeAction()` 工具执行（:280-294）→ `summarizeAnswer()` 汇总（:296-317）；`recallMemory` 循环外一次召回，`finalizeResponse` 回填 memoryUsed + 写回 short 记忆；`callModel/callModelStream` 统一 LLM 调用 |
| `ReactDecisionParser.java` | 解析模型 JSON 决策（含兜底） |
| `ReactResponseFormatter.java` | 观测上下文滚动拼接（`appendContext`） |
| `controller/ReactController.java` | SSE 流式对话入口 |

**已知边界**：NEED_MORE_EVIDENCE 转移边已定义但零使用（证据不足不会真的再检索）；JUDGING/REFLECTING 是轮次映射的进度标签，非语义判定。

---

## 五、上下文工程（Context Engineering）

**职责**：像管理内存一样管理上下文——准入、预算、组织、隔离、衰减、观测。

| 主题 | 代码位置 |
|---|---|
| 生成 prompt 三段式拼装 | `rag/HybridRagAnswerService.java:155`：`"用户问题:%n%s%n%n上下文:%n%s%s%n"`（问题 → 检索证据 → 记忆段）；`buildContext()`（:329）证据 `[n] source=... chunk=...` 格式化 |
| 记忆注入 | 两种方式：**advisor 注入**（`memory/MemoryInjectionAdvisor`，chat/客服/PDF RAG/工作流 ReAct 四链路 —— SystemMessage 首插不落 ChatMemory、不逐轮累积）与**手工拼段**（评测 HybridRagAnswerService / react ReactAgentService —— 无 ChatMemory 的链路拼 user prompt 无重放问题）。user 键 = `security/UserContext.currentUserId(fallback)` 认证主体（匿名回落 chatId），画像跨会话可召回；注入预算：short 5 / long 10 / fact 5 条 |
| System prompt | `constants/SystemConstants.java`：CUSTOMER_SERVICE_SYSTEM（客服小星）、RAG_ANSWER_SYSTEM、HYBRID_RAG_ANSWER_SYSTEM |
| 会话内记忆 | `config/MysqlChatMemory.java` + `repository/`（ChatHistoryRepository 的 InMemory/Mysql 实现）+ `util/ConversationIdHelper`（会话 ID 派生：prefix+chatId） |
| 上下文预算（工具侧） | `HarnessPayloadSanitizer`（观测裁剪）、`HttpMcpToolAdapter` 2MiB 响应上限、`WorkspaceRuntime` 搜索截断/文件大小上限 |
| 上下文预算（记忆侧） | 写入截断（Q 200 / A 400 / 结论 600）、事实单次 3 条、画像规范化去重 |
| 隔离 | tenant_id 贯穿检索与记忆（`security/TenantContext`）；画像提取独立会话 ID（memory-extract:{chatId}）防上下文角色互串 |
| 衰减 | short 24h / task 30d TTL + `cleanExpiredMemories()`（每天 3 点）；EXPIRE/USE 事件留痕 |
| Token 记账 | `service/TenantCostService.java`：`estimateTokens/assertBudget/recordUsage`；模型分档 `llm/ModelRouter.java`（economy/balanced，按场景路由） |
| 使用审计 | `HybridRagResult.memoryUsed`（实际注入的记忆标签，`memoryUsedLabels()` :265 组装、字段 :357）；用量记账端点 `rag_hybrid`（:162，聊天 RAG 与评测共用，PDF 问答仍记 `rag`） |

**已知瑕疵**：token 估算未把 memorySection 计入（`HybridRagAnswerService.java:148`），记账略低估。

---

## 六、评估与测试（Evaluation & Testing）

### 评测服务（`evaluation/`）

| 类 | 职责 |
|---|---|
| `EvaluationService.java` | 评测主服务：加载数据集 → 逐 case 调 `HybridRagAnswerService.answer()`（:261 起，conversationId 用 `ConversationIdHelper.build("eval", chatId)`）→ 打分 → 落库；`deleteDataset()` 在同一事务内按 结果 → 运行 → 用例 → 数据集 级联清理四张表（无外键、仅逻辑引用），未知数据集直接报错不触碰其它表 |
| `EvaluationScorer.java` | 打分器（命中率/引用正确性等指标） |
| `EvaluationReportRenderer.java` | 评测报告渲染 |
| `EvalCitationFormatter.java` | 纯函数转换：RAG 回答的引用去重成 sourceType:title:chunkId、证据只留非空片段，落 eval_result 前统一整形 |
| `EvalDatasetRecord` / `EvalCaseRecord` / `EvalRunRecord` / `EvalResultRecord` + Mapper | 数据集/用例/运行/结果四层模型（eval_dataset / eval_case / eval_run / eval_result 表），各 Mapper 均带按租户 + 数据集的级联删除 |
| `controller/EvaluationController.java` | 评测 REST 面：数据集创建/列表/删除、触发运行、基线标记、基线对比、Markdown 报告导出 |
| `vo/` | 请求与展示 VO（数据集创建、删除回执、运行请求、指标汇总、对比） |

### 评测与回归脚本（`scripts/`，Python）

| 脚本 | 职责 |
|---|---|
| `generate_eval_dataset.py` | 生成评测数据集 |
| `eval_demo.py` / `eval_live_runner.py` | 演示/在线评测执行器 |
| `run_regression.py` | 回归门禁（效果变化可量化） |
| `generate_eval_report.py` / `generate_eval_studio.py` | 评测报告生成与可视化 |
| `verify_ragproof_contract.py` | RAG 契约校验 |
| `generate_eval_contract_fixture.py` | 契约 fixture 生成 |

### 反馈闭环

| 类 | 职责 |
|---|---|
| `service/AnswerFeedbackService.java` | 答案反馈采集，追加写 `evaluation/feedback_dataset.jsonl`（数据集轮转） |
| `controller/FeedbackController.java` + `domain/AnswerFeedback*` | 反馈提交接口与模型 |

### 测试清单（69 个文件、295 个测试，全 mock 无外部依赖）

| 包 | 测试类 | 覆盖点 |
|---|---|---|
| `agent/harness/`（10 个） | ActionPolicyGuardTest、AgentHarnessServiceTest、AgentObservationTest、BuiltinToolRuntimeTest、HarnessEvaluationTest、HarnessEventRecorderTest、HttpMcpToolAdapterTest、McpToolRuntimeTest、TrustedActionServiceTest、WorkspaceRuntimeTest | 策略守卫、动作分发、SSRF 防护、工作区安全 |
| `agent/workflow/`（4 个） | AgentWorkflowEngineTenantIsolationTest、WorkflowReactAgentServiceStreamTest、WorkflowTaskReclaimerTest、WorkflowTaskStartupSweeperTest | 引擎租户隔离 + DONE 才写任务记忆；SSE 流式；孤儿任务回收；启动清扫（守卫输家不计数、故障不阻断启动） |
| `agent/research/`（1 个） | DeepResearchServiceTest | 剧本状态迁移与失败重抛；异步受理形状、后台在受理 taskId 名下跑完（防重复落库回归）、队列满拒绝、shutdown 幂等 |
| `memory/`（6 个） | MemoryServiceTenantIsolationTest、MemoryInjectionAdvisorTest、ChatTurnMemoryRecorderTest、TaskConclusionMemoryRecorderTest、RagFactMemoryRecorderTest、MemoryExtractionServiceTest | 四层写入时机、截断、去重、故障降级、租户隔离；advisor 注入契约（system 首插/user 原文不动/未传参透传/召回失败降级） |
| `rag/`（5 个） | HybridRagAnswerServiceMemoryTest、HybridRagAnswerServiceJudgingTest、HybridRagAnswerServiceIrrelevanceTest、RagAnswerServiceRerankTest、RagAnswerServiceRetrieveFallbackTest | 记忆注入断言 + 召回失败降级；判分消费（排序/垃圾线/降级回检索序）；无关线兜底闭环（全低于线不调模型回固定话术 / 放宽重试捞回走全管线且补向量权重 / 重试故障降级不炸 / 重试仍低线拒答）；重排分母 + 会话加成；检索异常兜底 |
| `retrieval/`（9 个） | HybridRetrievalServiceTest、IdentityRerankerTest、VectorRetrieverScoreTest、KeywordRetrieverTest、GraphRetrieverTest、EvidenceJudgeServiceTest、LexicalMatcherTest、ChatScopeTest、RetrievalPreviewServiceTest | 加权融合、去重计数、分数下限、租户级过滤断言；真取消超时与按路计数（error/timeout 断言轮询等落表）、排队不烧超时预算、队列满 saturated、web 禁用短路、停机中断兜底完成 promise、排队时长指标；中文 bigram 命中、长文档不稀释、会话加成；时效度激活；切词/召回分契约；软作用域加成封顶；图谱路与试搜降级 |
| `ingestion/`（4 个） | IngestionServiceTest、IngestionServiceGraphHookTest、DocumentGraphBackfillServiceTest、DocumentContentServiceTest | 入库解析/文档删除级联；图谱抽取钩子与存量回填；内容预览的筛选/截断/404·500 语义 |
| `service/`（4 个） | ReactAgentServiceTest、ReactDecisionParserTest、ReactResponseFormatterTest、TenantCostServiceTrendTest | ReAct 决策解析与格式化；用量趋势缺天补零 |
| `controller/`（9 个） | AgentHarnessControllerWebMvcTest、AuthControllerWebMvcTest、IngestionControllerWebMvcTest、JavaApiContractTest、MemoryControllerTest、AdminControllerWebMvcTest、AdminControllerSecurityTest、ChatControllerMemoryTest、DeepResearchControllerWebMvcTest | Web 层契约；管理员总览跨租户可见性；chat 链路记忆注入断言；深度研究 202 受理形状与 429 |
| `evaluation/`（2 个） | EvaluationScorerTest、EvaluationServiceTest | 打分逻辑；评测集删除级联清理与未知集拒绝 |
| `security/`（6 个） | ApiKeyOrJwtAuthFilterTest、DefaultFileSafetyScannerTest、JwtServiceTest、RateLimitFilterTest、RequestContextFilterTest、UserAuthServiceTest | 认证、限流、文件安全扫描（PDF/Word/Markdown 魔数）；注册/登录 |
| `graph/` | GraphExtractionServiceTest | LLM 实体抽取入库 |
| `config/`（4 个） | FlywayMigrationVersionTest、MysqlChatMemoryTest、ProdProfileConfigTest、SecurityDefaultsTest | 迁移版本、配置安全默认值 |
| 其他 | ModelRouterTest、HashUtilsTest、MysqlContainerSmokeTest（集成）、TestVector（@Disabled 需外部模型） | |

**运行**：`mvn test`（当前基线 292 个测试全绿；3 个跳过 = TestVector 需外部模型 ×2 + WorkspaceRuntimeTest 平台相关 ×1）。

---

## 端到端请求路径速查

| 入口 | 链路 |
|---|---|
| `POST /ai/pdf/chat` | PdfController → RagAnswerService（向量检索 + 本地重排 + 引用） |
| 评测 API | EvaluationController → EvaluationService → HybridRagAnswerService（四路混合 + 证据判分 + 记忆注入 + 引用） |
| React SSE | ReactController → ReactAgentService（reason/execute/summarize 循环）→ AgentHarnessService → 各 Runtime |
| MCP 天气查询 | React/WorkflowController → ReactAgentService / WorkflowReactAgentService（规划器选 mcp_call，白名单放行）→ AgentHarnessService → McpToolRuntime → HttpMcpToolAdapter（JSON-RPC over HTTP）→ mcp-weather 壳容器 → Open-Meteo |
| 深度研究 | DeepResearchController → DeepResearchService.createResearch（异步受理 202）→ researchExecutor 后台池 → executeResearch（剧本）→ AgentWorkflowEngine（状态机落库）→ Planner/Writer Agent；前端按 taskId 轮询 GET /tasks/{id}，DONE 后 GET /tasks/{id}/report |
| 记忆管理 | MemoryController → MemoryService（按 userId 查询/任务结论查询 /task/{taskId}/事件链/写入） |
| 文档入库 | IngestionController → IngestionService → 队列（RabbitMQ/Redis Stream/DB 轮询）→ IngestionWorker |

---

## 前端控制台（frontend/src，Vue 3 + Element Plus）

**结构**（2026-10 从 6552 行单文件 `App.vue` 拆出，行为零变化、无新增依赖）：

| 层 | 位置 | 职责 |
|---|---|---|
| 骨架 | `App.vue`（约 185 行） | 登录门闩 AuthGate + app-shell 三栏布局 + 页面级 `v-if/v-else-if` 链 + 定高框架样式；setup 首条语句 `initChatFromActiveSession()`，随后按原声明顺序调各模块 `registerXxxEffects()`，onMounted/onBeforeUnmount 保留原文 |
| 骨架组件 | `components/`：AuthGate、IconRail、SessionSidebar、WorkspaceHeader、SettingsDialog、BranchDrawer | 纯展示；状态/动作直接从单例组合式函数导入，无 props/emits |
| 页面组件 | `components/chat/ChatView.vue`（消息流虚拟滚动 + 输入区，最后拆、整块搬）、`components/knowledge/`、`components/admin/`、`components/usage/`、`components/evaluation/` | 显隐条件留在 App.vue 的组件标签上（EvaluationView 保持裸 `v-else`：非管理员误入 admin 视图时渲染评测页的兜底语义） |
| 状态组合式函数 | `composables/`（persistence + useAuthState/useGlobalUi/useChatState/useChatViewport/useUsage/useEvaluation/useKnowledge/useAdmin/useComposerUpload/useSessions/useResearch/useChatEngine/useAuthActions/useViewActivation） | 模块顶层 ref = 无依赖迷你 store；**硬规则**：watch/生命周期不进 `useXxx()`，只放各模块 `registerXxxEffects()` 由 App.vue 各调一次；页面组件不自带 onMounted 拉数据（切页卸载重挂会重复请求），懒加载统一在 useViewActivation |
| 持久化 | `composables/persistence.ts` | 不反向依赖功能模块；各模块顶层 `registerPersistSlice(key, getter)` 注册自己的切片，`persistState()` 聚合写 `localStorage['knowledgeops-agent-react-console-v2']`（key 名不变） |
| 纯函数 | `utils/`：constants、dom、format、evalFormat、models、markdown | markdown.ts 顶层完成 marked + hljs + DOMPurify 初始化（模块副作用随 import 生效），导出 `renderMarkdown` |
| 共享样式 | `styles/shared.css`（main.ts 在 element-plus CSS 之后导入） | 被 2+ 组件共用的类全局可见；**⚠ 顺序敏感勿重排**：`.kb-main-panel`（flex）与 `.eval-main-panel`（grid）特异性相同、靠源码顺序决胜（知识库主面板两个类同时挂，实际生效的是 grid），响应式覆盖与基础规则同住一个组件 |

**门禁**：`npm run type-check && npm run lint && npm run build` 三道全绿为收尾条件。
