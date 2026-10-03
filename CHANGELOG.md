# 更新日志

本文件记录本项目的所有重要变更。

## [Unreleased]

### 新增
- 评测集删除：`DELETE /ai/evaluation/datasets/{datasetId}` 在同一事务内按 结果 → 运行 → 用例 → 数据集 的顺序级联清理四张表（表间无外键、只有 `dataset_id` 逻辑引用），返回各层清理条数供前端提示；未知数据集直接报错且不触碰其它表。权限与评测写侧一致（`PERM_EVAL_WRITE`/ADMIN），只读角色不能删。前端评测页每个数据集卡片带 ✕ 删除按钮，二次确认后调用并按回执展示「N 题 / N 轮 / N 条结果」。
- 知识库文档清单：`GET /ingestion/documents` 按租户列出本租户文档（每个 chat 取最新任务，分页 + 文件名/批次搜索）；`DELETE /ingestion/documents/{chatId}` 级联清理向量切片 → 磁盘原文件 → 任务记录，任一步失败即中止。控制台知识库页新增「文档清单」标签页，本租户全员可查可删。
- 知识库试搜：`GET /ingestion/search?q=&topK=` 只走向量/关键词/图谱三条本地检索路返回原始命中（不调 LLM、不出答案），响应携带 `degradedSources` 标注降级路；控制台知识库页新增「试搜」标签页。
- 会话重命名：控制台侧栏支持会话重命名，重命名与自动持久化解耦，自动保存不会把用户新改的标题覆盖回旧值。
- 上传文档扩格式：入库入口在 PDF 之外支持 doc / docx / md（Tika 解析），`DefaultFileSafetyScanner` 按后缀白名单 + 魔数校验（PDF `%PDF-`、doc OLE2、docx ZIP 头），EICAR 恶意样本拦截对全部格式保留；`source_type` 按后缀推导（PDF/DOC/DOCX/MD），入库任务指标标签跟随后缀。
- 用量统计：`GET /cost/trend` 返回本月按天请求/Token/成本趋势（缺天补零），控制台新增「用量」页签展示预算、当日/当月消耗与趋势。
- 管理员跨租户文档总览：`GET /admin/documents` 汇总全部租户的文档与最新入库任务状态，`DELETE /admin/documents/{tenantId}/{chatId}` 供管理员清理任意租户文档；控制台对应页面仅 ADMIN 角色可见。
- 工作流引擎切换：控制台主聊天可在 standard（ReAct）与 workflow（`/ai/workflow/react/chat*`）两套引擎间切换；workflow SSE 真流式增量发帧，断连与孤儿任务由兜底逻辑统一收尾为终态。
- 知识图谱写入侧：文档入库完成后由 LLM 抽取实体/关系/事实写入 `kg_entity` / `kg_relation` / `kg_fact`，图谱读侧适配中文文本；存量文档可经 `POST /ingestion/documents/{chatId}/graph/build` 同步补建，文档删除联动清理对应图谱数据。
- 混合检索韧性：单路超时改为真取消底层任务（排队跳过、运行中中断），按路输出 success/error/timeout 指标并在结果携带 `degradedSources`；RAG 空结果有兜底文案，不再把局部故障伪装成「知识库为空」；topK 按来源轮转配额。
- 深度研究异步化：`POST /ai/research/tasks` 受理即返回 202 + taskId（report 为空、状态 PLANNING），执行转入专用后台线程池（`app.research.worker-count`/`queue-capacity` 可配，默认 3/20），HTTP 请求不再随 1-3 分钟的研究时长挂住；进度经 `GET /ai/research/tasks/{taskId}` 轮询，报告经 `GET /ai/research/tasks/{taskId}/report` 获取，队列打满返回 429 并守卫式放弃任务（`research.task.rejected` 指标、`research.pool.active/queued`/`research.queue.wait` 观测）。前端改为按 taskId 精确轮询单任务（修掉同租户他人研究串台文案），完成后自动拉报告，「停止」按钮语义变为停止观察（后台任务照跑）。新增启动清扫器 `WorkflowTaskStartupSweeper`：进程启动即把上一进程遗留的非终态任务守卫式收尾（`agent.workflow.task.swept` 指标），`WorkflowTaskReclaimer` 从此退化为 5 分钟兜底保险丝而非必备补丁。
- 检索池容量语义修正：单路超时从任务真正开始执行起算——排队等待不再占用超时预算，高并发下第 5 个之后的请求排队而不是集体「超时降级」拿到空结果；单路队列改有界（`app.retrieval.queue-capacity`，默认 64），池与队列打满时提交被拒、立即降级并新增 `saturated` 结局计数；新增 `retrieval.queue.wait` 按路排队时长指标；web 路禁用（`app.web-search.enabled=false`）时不再提交任务，不占四分之一的线程槽、不产生按路计数。`app.retrieval.pool-size`（默认 16）须 ≤ 数据库连接池 `DB_POOL_MAX_SIZE`（默认 20）——vector/keyword/graph 三路均直连数据库，扩线程前先扩连接池。
- 用户自助注册与密码登录：`POST /auth/register` 创建 USER 角色账号并补齐会话/评测/知识库读侧权限，`POST /auth/login` 签发 JWT；控制台新增登录/注册门闩。
- 评测页中文化，评测结果明细展开可见模型原始回答。
- 知识库页与文档问答页：拖拽上传、入库任务轮询、单文档范围问答（`docScoped` 硬过滤，单文档问答不受全库内容干扰）、文档删除。
- 记忆闭环：会话短记忆落库、高置信 RAG 证据固化为租户事实、工作流 DONE 落任务结论、画像提取达标升级长期记忆、召回注入 chat/react 链路并上报 `memoryUsed`；记忆 user 键升级为认证主体，advisor 注入不再逐轮累积记忆段。
- MCP HTTP 工具调用的瞬时网络故障在适配层重试，不再把抖动当工具失败喂给模型。
- 一键发布脚本 `scripts/publish.sh`（SSH 密钥免密：本地打包 → 上传 → 服务器重建容器），Dockerfile 切换为 jar 预构建模式并新增生产部署物料（`deploy/`）。

- 前端控制台拆分重构：6552 行的单文件 `App.vue` 拆为「骨架 + 组件 + 组合式函数」——`App.vue` 只剩约 185 行壳（登录门闩 + 页面级 v-if/v-else-if 链 + 定高框架样式），11 个组件（骨架 6 件：AuthGate / IconRail / SessionSidebar / WorkspaceHeader / SettingsDialog / BranchDrawer；页面 5 件：ChatView / KnowledgeView / AdminView / UsageView / EvaluationView），15 个模块级单例组合式函数（`composables/`：模块顶层 ref 即迷你 store，组件直接导入、无 props/emits；watch 与生命周期统一收敛到各模块幂等的 `registerXxxEffects()`，只由 App.vue 按原声明顺序各调一次，杜绝多组件重复注册深度 watch），6 个 `utils/` 纯函数模块，跨组件共享样式独立 `styles/shared.css`（严格保持原级联顺序：`.kb-main-panel` 与 `.eval-main-panel` 同特异性、靠源码顺序决胜，构建产物已按字节偏移复核）。不引入新依赖（无 Pinia/router/KeepAlive），不拆消息行子组件；localStorage 持久化 key 与 JSON 字段逐字段一致（字段顺序按切片注册序，与旧版实现可能不同，JSON.parse 读取不受影响）。
### 变更
- 【破坏性】`POST /ai/research/tasks` 从同步执行改为异步受理：响应从「200 + 完整报告」变为「202 + taskId（report 为空）」。外部脚本需改为轮询 `GET /ai/research/tasks/{taskId}` 至 DONE/FAILED，再从 `GET /ai/research/tasks/{taskId}/report` 取报告；队列满返回 429。控制台前端已同步适配。
- 聊天主界面布局改版：主聊天区定高、输入区工具按钮图标化，上传入口文案「上传 PDF」改为「上传文档」并提示支持格式。
- ReAct 规划提示词与兜底文案中文化。

### 修复
- 深度研究异步受理的重复落库：`executeResearch` 在后台又自行 `startTask` 一次，导致客户端拿到的 taskId 永远停在 PLANNING、真正的执行与报告挂在另一条重复任务名下（每次受理落库两条任务）。现在任务只在受理时落库一次，`executeResearch` 推进传入的任务记录；新增用例锁定「后台剧本必须在受理返回的 taskId 名下跑完且 startTask 仅一次」。同时补上状态机 `RETRIEVING → WRITING` 合法转移边（深度研究检索完直接成稿，此前被守卫拒绝、写报告期间状态一直停在 RETRIEVING）。
- 混合检索的停机兜底：单路 worker 的异常收尾从 `catch (RuntimeException)` 放宽到 `catch (Exception)`——停机中断（`InterruptedException` 是受检异常）此前接不住，promise 永不完成，调用线程 `join` 永挂、优雅停机卡死。现在任何非 Error 路径都保证 promise 被完成。
- Workspace shell 命令在 `waitFor` 被中断时不再泄漏子 `Process`：进程现在总会在 `finally` 块中被销毁（PMD `CloseResource` 此前也标记了该问题，并导致构建失败）。
- PMD `CloseResource` 规则现在将 `destroy()`/`destroyForcibly()` 视为关闭 `Process`，因此 `workspace_run_shell` 动作不再触发该检查。
- 流式 ReAct 请求（`/ai/react/chat/stream`）在流出错时现在会把工作流任务标记为 `FAILED`，而不是留下滞留在非终态的孤儿任务记录。
- 每步的 `input_tokens` 现在由 `AgentStepMapper.completeStep` 持久化，不再被静默丢弃（该列在表结构中早已存在，但从未写入过）。
- `WorkspaceRuntimeTest.runsOnlyAllowedCommandFamilies` 现在断言 workspace 目录名而不是完整绝对路径，修复了 Windows/Git-Bash 下 `pwd` 返回 MSYS 风格路径导致的失败。
- `IngestionService.processQueuedJob` 现在要求任务所属租户才能认领任务，封堵了跨租户劫持路径——此前任何调用方都可以把别的租户的 `jobId` 传给 `POST /ingestion/jobs/process` 来触发任务。新增的 `processQueuedJob(jobId, tenantId, traceId)` 重载也允许 Redis/RabbitMQ/db 轮询 worker 传入任务自身的租户（这些线程没有 MDC）。`IngestionJobMapper.claimForRun` 的 SQL 现在按 `tenant_id` 过滤。
- 修复图谱与会话搜索中的 SQL `LIKE` 关键字注入：`GraphService.searchEntities / searchFacts` 与 `AgentSessionService.list` 现在把用户输入的关键字经由新增的 `SqlLikeUtils.escapeForLike` 处理，`%`、`_`、`\` 不再扩大搜索范围。否则，搜索 `%` 会匹配租户 `kg_entity` / `kg_fact` / `agent_session_state` 表中的所有行，使这些端点中的任何一个都可能沦为单请求 DoS / 数据耗尽攻击向量。
- 前端反向 tabnabbing 加固：`App.vue` 的 `renderMarkdown` 现在 (a) 显式禁止 `style`/`onload`/`onclick`/`onerror`/`onmouseover` 属性以及 `style`/`iframe`/`object`/`embed`/`form`/`input` 标签，(b) 安装模块级 DOMPurify `afterSanitizeAttributes` 钩子，为所有带 `target="_blank"` 的链接强制添加 `rel="noopener noreferrer"`。这封堵了用 `v-html` 渲染被提示词注入的 LLM 输出时产生的反向 tabnabbing 攻击向量。
- Web 搜索后端在首次调用的惰性初始化上不再有竞态：`BingSearchBackend` 与 `SearXNGBackend` 现在使用 `volatile` 字段加双重检查锁，并发的首次调用方不会再拿到配置了一半的 `RestTemplate`（连接/读取超时不一致），也不会静默丢弃两个已构造实例之一。这两个后端还共享 Spring 管理的 `ObjectMapper` bean，而不是每个后端实例各自新建一个默认 `ObjectMapper`，因此搜索 JSON 解析与应用其余部分使用相同配置（包括已注册的模块）。
- `ChatController.multiModalChat` 对缺少显式 `Content-Type` 头的 multipart 上传不再返回 500：现在会回退到 `application/octet-stream`，而不是让 `Objects.requireNonNull(getContentType())` 抛出 NullPointerException，失败表现为模型层干净的 4xx 而非未处理的 NPE。
- 反馈数据集写入现在有大小上限，防止磁盘填满型 DoS：`AnswerFeedbackService.appendToDataset` 在文件达到新增的 `app.feedback.max-dataset-bytes` 上限（默认 50 MiB）时，会把 `feedback_dataset.jsonl` 文件（位于 `app.feedback.dataset-path` 之下）轮转为带时间戳的同目录文件。新增的 `FeedbackProperties.maxDatasetBytes` 字段可配置，运维人员可按环境调整阈值。否则，任何拥有 `PERM_FEEDBACK_WRITE`（或 `PERM_CHAT_WRITE` 加 `ROLE_ADMIN`）的调用方都可以通过反复提交反馈耗尽磁盘，因为原先的实现只做追加写入。
- `MemoryItemMapper` 现在对 PR #137 遗漏的三条查询（`findByUser`、`findByTenantAndTaskId`、`findByTenantAndMemoryId`）应用 `expires_at` 条件。缺少该条件时，过期记忆仍会返回给调用方，包括会将其注入 RAG 提示词的 `MemoryService.buildContext`。
- MCP HTTP 适配器现在可防 SSRF：`HttpMcpToolAdapter.isSafeBaseUrl`（以及 `supports` / `resolveUri` 中相应的守卫）会拒绝非 http(s) 的 baseUrl、无法解析的 baseUrl，以及主机解析到回环、链路本地、站点本地、多播或任意本地地址的 baseUrl。这封堵了 Agent 调用 `mcp_call` 动作（或配置错误的 `app.agent-harness.mcp.servers.<x>.base-url`）被重定向到内部服务或云实例元数据端点（如 `http://169.254.169.254/latest/meta-data/`）的路径。该守卫还辅以由运维维护的 `app.agent-harness.mcp.allowed-hosts` 白名单（精确主机或 `.suffix` 后缀匹配），供需要指向 localhost mock 的开发/测试环境使用；`HttpMcpToolAdapterTest` 通过该白名单接入。
- 服务位于反向代理之后时，限流 IP 键现在反映真实客户端：`RateLimitFilter.resolveClientIp` 仅在直连对端是私有/回环地址（即确有可信代理）时才遍历 `X-Forwarded-For`，并取最右侧的非私网跳，攻击者无法伪造最左侧条目来轮换自己的限流桶。否则，部署在 nginx / k8s ingress / ALB 之后时，所有匿名调用方都会落入同一个 `127.0.0.1` 桶，单个攻击者即可耗尽整个租户的按 IP 限流额度。
- Workspace 的 `propose_patch` / `apply_patch` 现在遵守与 `read_file` 相同的 `app.agent-harness.workspace.max-file-bytes` 上限。没有该上限时，行为异常或恶意的 LLM 驱动 Agent 调用可以提交数 MB 的内容/补丁，迫使 worker 分配等大的字符串并把数 MB 的文件写入磁盘。补丁分支还会对应用后的内容再次检查上限，因此小补丁扩展成巨大文件的情况仍会被拒绝。
- `WorkspaceRuntime.runCommand`（`workspace_run_shell`）现在拒绝会让 shell 执行命令或读取宿主文件的 ripgrep 选项。`rg --pre=<cmd>`、`rg --pre-glob=<cmd>`、`rg --hostname-bin=<file>`、`rg --regexp-file=<file>` 都会在进程启动前被拒绝，因此被提示词注入或配置错误的 `workspace_run_shell` Agent 调用无法再从"搜索文本"升级为"执行任意命令"或"读取任意宿主文件"。拒绝逻辑会剥离可选的 `=value` 后缀，因此 `--pre=evil` 与 `--pre evil` 会被同样拦截。

## [1.0.0] - 2026-04-28

### 新增
- 带 DLQ、重试重新入队与多 worker 并发的 Redis Stream 入库队列。
- RabbitMQ 入库队列后端，含专用队列/DLX/DLQ 声明与并发监听器。
- pgvector 正式迁移与回滚脚本。
- API Key 生命周期（签发/轮换/吊销/过期）与 JWT Refresh Token 流程。
- 权限粒度的安全路由与审计日志保留清理调度器。
- RAG 切片、重排、多文档融合与答案引用。
- 可观测性栈模板（Prometheus、Loki、Tempo、Alertmanager、Promtail）。
- OpenAPI 集成、压测脚本、大规模每夜评测流水线。
- ReAct Agent 端点（`/ai/react/chat`、`/ai/react/chat/stream`），含 trace 载荷与 SSE 事件。
- Vue3 + TypeScript + Element Plus 前端控制台，支持 Markdown 渲染、暗色模式、响应式布局与 ReAct trace 视图。
- Docker Compose 中的 Nginx 反向代理 web 服务，实现一条命令启动全栈。
- 开发演示管理员 API Key 种子数据（`dev-admin-key-2026`），用于本地鉴权流程演示。
- 快速 Maven 测试通道，外加独立的 `integration-test` profile 用于容器化 smoke 测试。
- 流式 SHA-256 哈希工具与 PDF 安全扫描器测试。
- Flyway 迁移 `V9`，为 `conversation` 与 `ingestion_job` 增加租户隔离。
- PostgreSQL pgvector 租户感知的元数据索引（`tenant_id`、`tenant_id + chat_id`）。

### 变更
- PDF 入库从数据库轮询循环切换为队列驱动的 worker 模型。
- API Key 轮换现在按稳定的 `keyName`（活跃密钥语义）进行，而不是生成临时名称。
- 向量存储后端默认值调整为面向 pgvector 生产路径。
- 项目命名与运行时标识统一为企业平台术语（`knowledgeops-agent`）。
- README 与文档升级为以企业部署/架构为核心的文档集。
- 非 development profile 下应用安全默认启用。
- 自动入库幂等键现在使用文件内容哈希，替代文件名加文件大小。
- PDF 安全扫描现在只读取文件头，并在入库前校验 PDF 魔数。
- 前端生产构建现在把 Vue、Element Plus 与 Markdown/高亮依赖拆分为独立的 vendor chunk。
- 聊天历史、聊天记忆、入库任务 API 与 PDF 下载/列表操作现在均按租户隔离（`tenant_id`），防止跨租户数据泄露。
- RAG 检索过滤器现在具备租户感知（`tenant_id && chat_id`），入库元数据包含 `tenant_id`。
- ReAct 流式端点现在输出真实的模型 token 流，而非人工拼接的答案分块。
- 成本预算更新端点在请求体缺少 `tenantId` 时，回退使用请求中的租户头。
- 入库运维指标的提交/完成/耗时序列现在包含租户标签。
