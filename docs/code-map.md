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

### Agent Harness（自研动作体系，`agent/harness/`）

| 类 | 职责 |
|---|---|
| `ActionSchema.java` / `ActionSchemaRegistry.java` | 动作 schema 与注册表；构造器一次性注册 11 个动作（builtin 4 + mcp_call 1 + workspace 6）；`find()` Optional 查找，读时不可变拷贝 |
| `AgentHarnessService.java` | 动作执行入口 `execute(AgentAction)`：校验 schema → 策略守卫 → 分发 runtime → 记录观测 |
| `ActionPolicyGuard.java` / `ActionPolicyDecision.java` | 调用前策略判定（允许/拒绝/需受信确认） |
| `TrustedActionService.java` | 受信动作：高危操作（如写库）先出预览，确认后执行，token 清理 |
| `BuiltinToolRuntime.java` | 内置动作执行：query_school/query_course/add_course_reservation/rag_search（rag_search 内部调 `rag/RagAnswerService`） |
| `WorkspaceRuntime.java` | 工作区动作：文件读写/搜索/受限 shell，mvn/git/ripgrep 白名单，文件大小与搜索结果截断 |
| `McpToolAdapter.java` / `HttpMcpToolAdapter.java` / `McpToolRuntime.java` | MCP 出站桥接：手拼 JSON-RPC 2.0 `tools/call`，JDK HttpClient 直连；SSRF 防护（拒绝 RFC1918/回环/云元数据地址 + allowedHosts 白名单）、响应体 2 MiB 上限 |
| `HarnessPayloadSanitizer.java` | 观测载荷消毒裁剪（防工具返回值撑爆上下文） |
| `HarnessEventRecorder.java` / `AgentObservation.java` | 执行留痕与观测结构 |
| `UnifiedDiffService.java` | 结构化 diff 输出（受信动作预览用） |

**调用链**：`ReactAgentService.executeAction()`（`service/ReactAgentService.java:280-294`）→ `AgentHarnessService.execute()` → 策略守卫 → 按 schema 分发到对应 Runtime → `AgentObservation` 回填 ReAct 轨迹。

**已知边界**：ReAct planner 动作白名单硬编码 5 个动作，不含 mcp_call（harness 层就绪、编排层未开放）。

---

## 二、检索（Hybrid Retrieval & Ingestion）

**职责**：四路并行召回 → 加权融合 → 去重 → 证据判分 → 引用构建；另有 PDF 入库与知识图谱两路数据供给。

### 混合检索（`retrieval/`）

| 类 | 职责 |
|---|---|
| `HybridRetrievalService.java` | 核心：`retrieve()`（:82）四路并行（CompletableFuture + 专用 8 线程池）→ `applyWeight()` 加权（:121，finalScore = retrievalScore × 权重）→ `deduplicate()` 去重（:141，内容前 200 字符规范化指纹，同指纹保留分高者）→ 按 finalScore 排序取 topK；单路异常返回空、3 秒超时静默降级（:138 `completeOnTimeout`） |
| `HybridWeights.java` | 权重配置：DEFAULT 0.40/0.25/0.20/0.15（向量/关键词/图谱/网络），预置 SEMANTIC/KEYWORD/BALANCED 档位，`normalize()` 归一化 |
| `VectorRetriever.java` / `KeywordRetriever.java` / `GraphRetriever.java` / `WebRetriever.java` | 四路各自实现；web 默认关闭（`app.web-search.enabled`） |
| `web/WebSearchBackend.java` + `SearXNGBackend` / `BingSearchBackend` / `WebSearchProperties` | 外部搜索适配层（SearXNG 自托管 / Bing API） |
| `Reranker.java` / `IdentityReranker.java` | 重排接口与恒等实现 |
| `ScoredDocument.java` | 检索结果统一结构（docId/sourceType/title/content/retrievalScore/finalScore） |
| `EvidenceJudgeService.java` / `EvidenceItem.java` | 证据判分：相关性/可信度评分，决定哪些证据进入生成 |
| `CitationService.java` / `CitationItem.java` | 引用构建与脚注格式化（`formatCitationFooter`） |

### 数据供给（`ingestion/`、`graph/`）

| 类 | 职责 |
|---|---|
| `ingestion/IngestionService.java` | PDF/文档入库：解析、分块、向量化、元数据（tenant_id/chat_id） |
| `ingestion/IngestionWorker.java` + `queue/`（RabbitMq / RedisStream / Noop / db_polling） | 异步入队消费，多后端可切换 |
| `graph/GraphService.java` + `KgEntityRecord` / `KgFactRecord` / `KgRelationRecord` | 知识图谱：课程/难度/主题实体与关系，供 GraphRetriever |
| `config/VectorStoreConfiguration.java` | pgvector VectorStore 装配（`OpenAiEmbeddingModel`） |

**调用链**：`HybridRagAnswerService.answer()` 第 1 步（`rag/HybridRagAnswerService.java:61`）→ `HybridRetrievalService.retrieve()` → 第 2 步证据判分（:78）→ 第 3 步引用（:86）。

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
| `rag/HybridRagAnswerService.java` | Step 2.5 事实沉淀（:83）+ Step 4.5 召回注入（:93-97）；`recallMemory()` 失败降级 null（:150-157） |

**API**：`controller/MemoryController.java` — `GET /ai/memory/query`（聚合查询）、`GET /ai/memory/{id}/events`（事件链，租户隔离）、`POST /ai/memory/save`（手动写入）。

---

## 四、工作流（Workflow State Machine & Orchestration）

**职责**：三层架构——引擎层（零业务智能）、编排层（剧本）、执行层（Agent）。复杂任务可观测、可审计、可回放。

### 引擎层（`agent/workflow/`）

| 类 | 职责 |
|---|---|
| `WorkflowState.java` | 状态枚举：CREATED→PLANNING→SEARCHING→RETRIEVING→JUDGING→REFLECTING→WRITING→DONE（另有 NEED_MORE_EVIDENCE/FAILED）；`canTransitionTo` 守卫合法转移 |
| `AgentWorkflowEngine.java` | 任务生命周期/状态管理/事件溯源：startStep/completeStep、状态落库、`completeTask` 触发任务结论记忆 |
| `AgentTaskRecord` / `AgentStepRecord` / `AgentEventRecord` + 各 Mapper | 任务/步骤/事件持久化（agent_task / agent_step / agent_event 表） |
| `WorkflowReactAgentService.java` | ReAct 迭代与状态映射：`mapToWorkflowState(step)`（:1→SEARCHING、2→RETRIEVING、3→JUDGING、4→REFLECTING、其余→WRITING） |
| `controller/WorkflowController.java` | 工作流任务提交与状态查询 |

### 编排层（`agent/research/`）

| 类 | 职责 |
|---|---|
| `DeepResearchService.java` | 深度研究剧本：拆题→检索→评证→反思→成稿 的状态转移与步骤编排 |
| `ResearchPlannerAgent.java` | 拆题（LLM 规划，含防御性提取） |
| `ReportWriterAgent.java` | 成稿（LLM 汇总） |
| `ResearchTaskRequest.java` | 任务请求结构 |
| `controller/DeepResearchController.java` | 深度研究入口 |

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
| 生成 prompt 三段式拼装 | `rag/HybridRagAnswerService.java:109`：`"用户问题:%n%s%n%n上下文:%n%s%s%s%n"`（问题 → 检索证据 → 记忆段）；`buildContext()`（:176）证据 `[n] source=... chunk=...` 格式化 |
| 记忆注入 | 两种方式：**advisor 注入**（`memory/MemoryInjectionAdvisor`，chat/客服/PDF RAG/工作流 ReAct 四链路 —— SystemMessage 首插不落 ChatMemory、不逐轮累积）与**手工拼段**（评测 HybridRagAnswerService / react ReactAgentService —— 无 ChatMemory 的链路拼 user prompt 无重放问题）。user 键 = `security/UserContext.currentUserId(fallback)` 认证主体（匿名回落 chatId），画像跨会话可召回；注入预算：short 5 / long 10 / fact 5 条 |
| System prompt | `constants/SystemConstants.java`：CUSTOMER_SERVICE_SYSTEM（客服小星）、RAG_ANSWER_SYSTEM、HYBRID_RAG_ANSWER_SYSTEM |
| 会话内记忆 | `config/MysqlChatMemory.java` + `repository/`（ChatHistoryRepository 的 InMemory/Mysql 实现）+ `util/ConversationIdHelper`（会话 ID 派生：prefix+chatId） |
| 上下文预算（工具侧） | `HarnessPayloadSanitizer`（观测裁剪）、`HttpMcpToolAdapter` 2MiB 响应上限、`WorkspaceRuntime` 搜索截断/文件大小上限 |
| 上下文预算（记忆侧） | 写入截断（Q 200 / A 400 / 结论 600）、事实单次 3 条、画像规范化去重 |
| 隔离 | tenant_id 贯穿检索与记忆（`security/TenantContext`）；画像提取独立会话 ID（memory-extract:{chatId}）防上下文角色互串 |
| 衰减 | short 24h / task 30d TTL + `cleanExpiredMemories()`（每天 3 点）；EXPIRE/USE 事件留痕 |
| Token 记账 | `service/TenantCostService.java`：`estimateTokens/assertBudget/recordUsage`；模型分档 `llm/ModelRouter.java`（economy/balanced，按场景路由） |
| 使用审计 | `HybridRagResult.memoryUsed`（实际注入的记忆标签，:160-174） |

**已知瑕疵**：token 估算未把 memorySection 计入（`HybridRagAnswerService.java:102`），记账略低估。

---

## 六、评估与测试（Evaluation & Testing）

### 评测服务（`evaluation/`）

| 类 | 职责 |
|---|---|
| `EvaluationService.java` | 评测主服务：加载数据集 → 逐 case 调 `HybridRagAnswerService.answer()`（:220-226，conversationId 用 `ConversationIdHelper.build("eval", chatId)`）→ 打分 → 落库 |
| `EvaluationScorer.java` | 打分器（命中率/引用正确性等指标） |
| `EvaluationReportRenderer.java` | 评测报告渲染 |
| `EvalDatasetRecord` / `EvalCaseRecord` / `EvalRunRecord` / `EvalResultRecord` + Mapper | 数据集/用例/运行/结果四层模型（eval_dataset / eval_case / eval_run / eval_result 表） |
| `controller/EvaluationController.java` | `POST /ai/evaluation/datasets/{id}/runs` 触发评测 |
| `vo/` | 请求与展示 VO（数据集创建、运行请求、指标汇总、对比） |

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

### 测试清单（43 个文件、123 个测试，全 mock 无外部依赖）

| 包 | 测试类 | 覆盖点 |
|---|---|---|
| `agent/harness/`（10 个） | ActionPolicyGuardTest、AgentHarnessServiceTest、AgentObservationTest、BuiltinToolRuntimeTest、HarnessEvaluationTest、HarnessEventRecorderTest、HttpMcpToolAdapterTest、McpToolRuntimeTest、TrustedActionServiceTest、WorkspaceRuntimeTest | 策略守卫、动作分发、SSRF 防护、工作区安全 |
| `agent/workflow/` | AgentWorkflowEngineTenantIsolationTest | 引擎租户隔离 + DONE 才写任务记忆 |
| `memory/`（6 个） | MemoryServiceTenantIsolationTest、MemoryInjectionAdvisorTest、ChatTurnMemoryRecorderTest、TaskConclusionMemoryRecorderTest、RagFactMemoryRecorderTest、MemoryExtractionServiceTest | 四层写入时机、截断、去重、故障降级、租户隔离；advisor 注入契约（system 首插/user 原文不动/未传参透传/召回失败降级） |
| `rag/` | HybridRagAnswerServiceMemoryTest | 记忆注入断言 + 召回失败降级 |
| `controller/`、`service/` | ChatControllerMemoryTest、ReactAgentServiceTest | chat/react 链路记忆注入断言、原始 prompt 写回（防自我循环）+ 召回失败降级 |
| `retrieval/`（3 个） | HybridRetrievalServiceTest、IdentityRerankerTest、VectorRetrieverScoreTest | 加权融合、去重计数、分数下限 |
| `service/`（3 个） | ReactAgentServiceTest、ReactDecisionParserTest、ReactResponseFormatterTest | ReAct 决策解析与格式化 |
| `controller/`（5 个） | AgentHarnessControllerWebMvcTest、AuthControllerWebMvcTest、IngestionControllerWebMvcTest、JavaApiContractTest、MemoryControllerTest | Web 层契约 |
| `evaluation/` | EvaluationScorerTest | 打分逻辑 |
| `security/`（5 个） | ApiKeyOrJwtAuthFilterTest、DefaultFileSafetyScannerTest、JwtServiceTest、RateLimitFilterTest、RequestContextFilterTest | 认证、限流、文件安全扫描 |
| `config/`（4 个） | FlywayMigrationVersionTest、MysqlChatMemoryTest、ProdProfileConfigTest、SecurityDefaultsTest | 迁移版本、配置安全默认值 |
| 其他 | IngestionServiceTest、ModelRouterTest、HashUtilsTest、MysqlContainerSmokeTest（集成）、TestVector（@Disabled 需外部模型） | |

**运行**：`mvn test`（当前基线 123 个测试全绿，2 个跳过为 TestVector 需外部模型）。

---

## 端到端请求路径速查

| 入口 | 链路 |
|---|---|
| `POST /ai/pdf/chat` | PdfController → RagAnswerService（向量检索 + 本地重排 + 引用） |
| 评测 API | EvaluationController → EvaluationService → HybridRagAnswerService（四路混合 + 证据判分 + 记忆注入 + 引用） |
| React SSE | ReactController → ReactAgentService（reason/execute/summarize 循环）→ AgentHarnessService → 各 Runtime |
| 深度研究 | DeepResearchController → DeepResearchService（剧本）→ AgentWorkflowEngine（状态机落库）→ Planner/Writer Agent |
| 记忆管理 | MemoryController → MemoryService（按 userId 查询/任务结论查询 /task/{taskId}/事件链/写入） |
| 文档入库 | IngestionController → IngestionService → 队列（RabbitMQ/Redis Stream/DB 轮询）→ IngestionWorker |
