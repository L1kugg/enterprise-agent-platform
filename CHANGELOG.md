# 更新日志

本文件记录本项目的所有重要变更。

## [Unreleased]

### 新增
- LLM 调用链弹性防护：新组件 `llm/ModelCallGuard` 把此前只定义未生效的 Resilience4j 三 Bean（CircuitBreaker / Retry / TimeLimiter）编程式织入全部 6 个 LLM 调用点——`ReactAgentService`（react）、`WorkflowReactAgentService`（workflow）、`RagAnswerService`（rag）、`HybridRagAnswerService`（rag-hybrid）、`ResearchPlannerAgent`（research-plan）、`ReportWriterAgent`（research-report），按场景独立熔断器。同步调用装饰顺序 `Retry(CircuitBreaker(调用))`：熔断器记录每次尝试，重试仅对瞬时异常白名单（`RestClientException`/`WebClientException`/`TimeoutException`/`TransientAiException`）生效，最多 3 次、间隔 2s；流式不重试（防重复吐字），改为订阅前 `acquirePermission` 快速失败 + 30 秒超时基线（Reactor `timeout`），终态信号回写熔断器。熔断参数不变：失败率≥50% 或慢调用≥50%（>10s）→ 开路 30s → 半开 5 探测。观测：resilience4j starter 自动发布 `resilience4j_circuitbreaker_*` tagged 指标（懒创建实例经 entry-added 事件挂上），自定义计数器 `llm.call.outcome{scenario, outcome=success/error/not_permitted}`。承接侧：两条 RAG 链生成步骤捕获熔断/调用异常返回固定兜底文案（`generation_fallback`），ReAct / Workflow / DeepResearch 由既有规则兜底承接；模型路由 fallback 链仍为路由层独立降级，与本防护互补。`ModelCallGuardTest` 7 例锁死透传计数/白名单重试/非白名单单次/熔断快速失败（同步+流式）/流式超时回写；7 个既有测试构造器补 `TestGuards.real()`。文档同步：PROJECT_DOC 10.2/16.3、README 三处、demo-paths、code-map。
- 主库只读查询动作 `query_database`：模型在主聊天里可现场生成 SQL 直接查询业务库任意表（text2sql）。四层防御：① `tools/SqlReadOnlyGuard` 正则只读守卫（剥注释/字符串字面量后校验单条 SELECT、写关键词整词黑名单含改数 CTE 与 FOR SHARE、`users`/`api_keys` 等凭证表黑名单、LIMIT 缺失自动追加/超限收敛到 30）；② 租户占位符硬校验（模型 SQL 里业务表过滤只能写 `tenant_id = '__TENANT__'`，执行前服务端替换为当前租户真实值——模型既不知道也写不出其他租户名，出现真实租户字面值或 `<>` 比较一律拒绝；information_schema 识别带 `tenant_id` 列的表 + 5 分钟 TTL 缓存，访问业务表却没写占位符时给出含占位符写法的修正提示，fail-open 设计）；③ 连接级只读会话 + 语句超时 5s；④ 驱动级行数硬顶（maxRows+1 探测截断）+ 单元格 200 字符截断。动作在 `ActionSchemaRegistry` 单点登记（required `sql`，无任何 optional 字段——租户从 `action.tenantId()` 服务端注入、不让模型传，防伪造），提示词/白名单/守卫自动跟上；配置挂 `app.agent-harness.database-query`（enabled/max-rows/query-timeout-seconds/max-cell-chars/max-sql-length/denied-tables，行数上限与观测消毒器集合截断 30 对齐）；守卫拒绝与 SQL 执行报错都转成带修正指引的 error 观测喂回模型重试（`MAX_STEPS=4` 内闭环）。守卫规则由 `SqlReadOnlyGuardTest`（16 例）锁死，`DatabaseQueryToolsTest` 覆盖停用短路/占位符校验与替换/只读参数/payload 形状（9 例）；`MysqlContainerSmokeTest` 的 Flyway 版本断言顺手从过期的 "14" 修到 "19"。线上冒烟曾发现首版租户过滤只查「SQL 里有没有 tenant_id 字样」、模型照示例写死 `tenant_id = 'public'` 即可读其他租户数据，本条占位符方案即该缺口的修复。
- MCP 外部工具接入，首个工具：天气查询。自研 JSON-RPC 2.0 桥接（`HttpMcpToolAdapter`）吃到第一个真实外部工具——翻译壳容器 `mcp-weather`（Python 标准库单文件 `mcp-weather/mcp_weather.py`，桥接 Open-Meteo 免费天气 API，无需钥匙）按 MCP 形状收发 `tools/call`，返回城市实时天气 + 当日温度的中文摘要。配置侧 `app.agent-harness.mcp.servers.weather`（base-url 指向 compose 内网容器名，经 SSRF `allowed-hosts` 白名单放行 `mcp-weather`）；两道规划白名单（`ReactDecisionParser` / `WorkflowReactAgentService`）与两处规划提示词放开并教会 `mcp_call`；`mcp_call` 从 trustedOnly 调整为聊天 ReAct 循环可直接调用（只读外部查询，风险由 SSRF 校验、2MiB 响应上限、按工具超时兜底；workspace 写/壳动作仍保持受信令牌确认，preview/execute 两段式流程依旧只收 trustedOnly 动作，`mcp_call` 因此不再走该流程、由聊天两条 ReAct 链路直接调用）；模型层还有 `disabled-actions` / 租户动作白名单两道熔断。
- 评测集删除：`DELETE /ai/evaluation/datasets/{datasetId}` 在同一事务内按 结果 → 运行 → 用例 → 数据集 的顺序级联清理四张表（表间无外键、只有 `dataset_id` 逻辑引用），返回各层清理条数供前端提示；未知数据集直接报错且不触碰其它表。权限与评测写侧一致（`PERM_EVAL_WRITE`/ADMIN），只读角色不能删。前端评测页每个数据集卡片带 ✕ 删除按钮，二次确认后调用并按回执展示「N 题 / N 轮 / N 条结果」。
- 知识库文档清单：`GET /ingestion/documents` 按租户列出本租户文档（每个 chat 取最新任务，分页 + 文件名/批次搜索）；`DELETE /ingestion/documents/{chatId}` 级联清理向量切片 → 磁盘原文件 → 任务记录，任一步失败即中止。控制台知识库页新增「文档清单」标签页，本租户全员可查可删。
- 知识库试搜：`GET /ingestion/search?q=&topK=` 只走向量/关键词/图谱三条本地检索路返回原始命中（不调 LLM、不出答案），响应携带 `degradedSources` 标注降级路；控制台知识库页新增「试搜」标签页。
- 知识库文档内容预览：「文档清单」点文件名打开抽屉，按入库切片顺序展示正文（PDF 块带页码标签）；后端 `GET /ingestion/documents/{chatId}/content` 复用入库同一条解析链路重解析磁盘原文件（只读、不写向量库），正文超 20 万字符截断并提示可下载原文件；抽屉内「下载原文件」经带认证的 fetch 取 blob（新标签页裸跳转带不了 JWT）。
- 执行轨迹真实耗时：standard 引擎每步掐表，`ReactTraceStepVO` 新增 `elapsedMs`（SSE trace 事件与最终响应轨迹都携带）；前端轨迹耗时「N 步 · Xms」优先累计真实值，没有 elapsedMs 的旧轨迹（工作流引擎/历史会话）退化按每步约 2 秒估算，不再恒显编造数字。
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
- 动作登记收敛单一来源：新增 Agent 动作从「改 ~9 处」降为「注册表登记 1 次」。`ActionSchema` 增加 `plannerHint` 字段（给规划器提示词的动作说明，空 = 提示词里只出现裸动作名）；新建 `PlannerActionCatalog` 从 `ActionSchemaRegistry` 生成两条 ReAct 链路共用的解析白名单与提示词动作列表——白名单口径 = trustedOnly=false 的动作 + finish（与策略守卫「聊天循环只能执行非受信动作」一致，workspace 写/壳动作自动排除）；`ReactDecisionParser` 的硬编码 `ALLOWED_ACTIONS`、workflow 引擎的内联名单与两处手写动作清单全部删除改为注入。执行侧（builtin 分发、mcp 配置、翻译壳）保持按工具逐个实现。workflow 引擎文件回到 500 行 Checkstyle 上限内；新增 `PlannerActionCatalogTest`，`ReactAgentServiceTest` 补「提示词确由注册表生成」断言。
### 变更
- 主聊天 `rag_search` 切换到四路混合检索：两条 ReAct 引擎的检索动作从 `RagAnswerService`（向量单路）改为调 `HybridRagAnswerService`（向量/关键词/图谱/网络并行召回 + 证据判分 + 记忆注入），评测链路同款管线；观测载荷形状不变（query/answer/citations/evidence/weights），前端零改动——citations 仍映射为前端可解析的 `source=文件名, chunk=块号` 文本（标题内半角逗号转全角防解析截断），evidence 为判分后的证据摘要，`weights` 回四路归一化实际值、轨迹色条随真实链路显示四路比例。无关问题防线随切换移植并补强：原始检索分低于 `rag.fallback-score-floor`（0.30）的文档不进判分/事实沉淀/生成（判分与事实沉淀只吃过线文档），四路全部低于线时向量路放宽阈值重试一次（异常降级不炸主流程），仍无过线文档则不调模型直接返回「没有在当前知识库中检索到可用内容。」。`VectorRetriever` 新增显式阈值重载供兜底通道使用；模型路由 `endpoint-profiles` 补 `rag_hybrid` 映射（评测链路同样受益）。用量记账里聊天 RAG 的端点标签从 `rag` 变为 `rag_hybrid`（用量统计按端点分桶，PDF 问答仍记 `rag`）。PDF 文档问答 `/ai/pdf/chat` 保持向量单路不变。
- 检索召回路色条改为按当次实况绘制 + 四路融合权重做成配置：前端轨迹条此前写死「四路 40/25/20/15」，且与主聊天实际链路不符——主聊天 `rag_search` 走 `RagAnswerService` 向量单路，四路混合检索（`HybridRetrievalService`）只有评测与深度研究在用。现在权重改为配置 `app.retrieval.weights.vector/keyword/graph/web`（默认 0.40/0.25/0.20/0.15，检索时自动归一化，结果回带实际生效值）；`RagAnswerService` 的观测载荷携带 `weights={"vector":1.0}`（如实标注单路）；前端色条照 `observation.weights` 绘制（向量单路显示 Vector 100%，无 weights 的历史消息不再画条）。
- 报错提示全面中文化：用户可见的错误文案（登录/注册校验、权限不足、参数不合法、任务/会话/评测集不存在、文件类型与魔数校验、限流 429、预算拦截等）全部改为中文；401 未登录提示「未登录或登录已过期，请重新登录」，服务器异常统一「服务器内部错误，请稍后重试」。前端 `client.ts` 错误格式同步改为「请求失败（错误码 NNN）：原因」，认证接口的 HTTP 200 + ok=0 业务失败不再显示错误码；约 33 处接口兜底文案改为中文，`isAuthError` 的 401 识别随新格式更新。9 处测试 msg 断言同步改中文，后端 274 个测试全绿。
- 【破坏性】`POST /ai/research/tasks` 从同步执行改为异步受理：响应从「200 + 完整报告」变为「202 + taskId（report 为空）」。外部脚本需改为轮询 `GET /ai/research/tasks/{taskId}` 至 DONE/FAILED，再从 `GET /ai/research/tasks/{taskId}/report` 取报告；队列满返回 429。控制台前端已同步适配。
- 聊天主界面布局改版：主聊天区定高、输入区工具按钮图标化，上传入口文案「上传 PDF」改为「上传文档」并提示支持格式。
- ReAct 规划提示词与兜底文案中文化。
- 评测产物归档：仓库根目录的评测运行结果 JSON（`eval-result*.json`）收拢到 `evaluation/results/`，根目录的两个评测驱动脚本（`eval-run.py` / `eval-run-fuzzy.py`）移入 `scripts/`，各驱动脚本输出路径同步指向 `evaluation/results/`；含主聊天切四路前的多文档抗干扰基线 `eval-result-multidoc.baseline.json`，供发布前后对比。

### 修复
- 聊天答案的来源不再重复展示两遍：两条 ReAct 链路（standard/workflow）此前都会往答案正文末尾追加「引用来源: [1] source=…」文字脚注，而前端又拿同一份结构化 citations 在答案下方渲染「来源引用」卡片列表，同一批来源出现两次。现在响应组装不再往正文拼脚注（structured citations 仍原样返回，前端卡片是唯一来源展示），成稿提示词同时叮嘱模型不要在正文里罗列来源清单；PDF 单文档问答页没有来源卡片，其正文脚注保持不变。改动前已保存的历史消息里旧格式的正文脚注仍会原样回放。
- 登录过期不再把人留在页面里反复报 401：登录门闩此前只看本地存没存令牌、不看不有效性，令牌过期（默认 120 分钟）后用户照样停在聊天页，每个请求各自弹错。现在全部接口共用的报错漏斗挂上全局 401 钩子——任何接口收到 401 即清掉过期凭据，登录页整页自动接管（并发 401 只弹一次「登录已过期，请重新登录」）。同时补上静默自动续期：前端记录令牌到期时刻（`expiresInSeconds`），剩余不足 3 分钟即用 14 天有效的 refresh token 换新令牌（定时器每分钟查 + 页面切回前台补查，成败都不弹提示），正常使用不会再被踢回登录页；续期通道也失效（连续 14 天未用）才落到 401 兜底。原「设置弹窗手动刷新」按钮行为不变。
- 前端反代对 app 的 502 根治：nginx `proxy_pass http://app:8080` 静态写法只在 nginx 启动时解析一次容器名，app 容器重建后内网 IP 变化（尤其新增/删除容器改变 IP 排布时）会让 `/api` 全量 502，只能重启 web 容器恢复。改为 Docker 内置 DNS（127.0.0.11）动态解析 + 变量形式 `proxy_pass`（`rewrite` 剥离 `/api/` 前缀），每 10s 重解析，此后发布重建 app 不再牵连 web。
- 完全无关的问题不再"答非所问还带引用"：`RagAnswerService` 的空结果兜底（严格阈值搜空后放宽阈值按最近邻重试）是给泛问（"这份文档讲了什么"）留的通道，但完全无关的问题在阈值机制下与泛问长得一样——最近邻照样被捞回、引用脚注照拼，用户问"推荐几个长春美食"也带回 6 条 Java 面试题引用。兜底捞回的结果现按绝对相似度再卡一条无关线（`rag.fallback-score-floor`，默认 0.30，须低于 `similarity-threshold`）：全部低于线视为完全无关，整批作废、不调模型直接返回"没有在当前知识库中检索到可用内容"；分数元数据缺失时保守保留，泛问通道不受影响。新增回归测试锁定"兜底捞回全低于线必须整批作废 / 泛问相似度在线上照常保留"。
- 评测/混合 RAG 多文档串文档：`HybridRagAnswerService` 用 `sourceType|chunkId` 把判分证据关联回原文档，但各文档入库时切片号独立从 0 编起——两份文档的同号切片（如都是 chunk-0）在该键上互相覆盖，后放的把先放的顶掉。后果：引用列表标的是 B 文档（引用在覆盖前构建、指向正确），进 prompt 的正文却是 A 文档的——模型"凭空丢了一份文档"，对跨文档问题如实回答"上下文没有该内容"，评测关键词分掉到 0.5（3 文档抗干扰评测两题 0.875 的根因；单文档场景不受影响，因为同号切片不会同时被召回）。关联键补上 `title`（文件名）后覆盖不再发生；新增回归测试锁定"两份文件同号切片都必须按各自正文进上下文"，复跑原评测集 7/7 满分。
- 流式对话退化成一次性返回（两层叠加）：① 四个 ChatClient 都挂着 `SimpleLoggerAdvisor`，其流式实现为打完整日志会经 `ChatClientMessageAggregator` 把上游逐字增量聚合成单个响应再吐出——所有流式端点（主聊天 ReAct/工作流、PDF 问答、客服、Agent 内部推理）都变成"长时间无输出、转完一次性出现"。替换为自研 `PassThroughLoggerAdvisor`：保留等价的请求/响应 DEBUG 日志，流式路径逐元素透传、完整内容在流收尾旁路记录（实测上游 32 个增量分片此前只剩 1 个 token 事件）。② ReAct 规划器是阻塞式推理，finish 决策里最终答案已整段生成，直接回答此前用 `Flux.just` 一次性发出——即使①修复后直答路径仍是一坨到达。新增 `AnswerStreamSupport.chunked`：把现成答案切成 6 字小片、每 15ms 发一片匀速播放，直答观感与真流式一致（纯展示层播放，不改变答案内容、不多花模型调用）。
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
