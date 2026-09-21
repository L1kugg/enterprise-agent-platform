# Review 整改总览（五轮）

> 五轮 code review 的问题定位、修复与验证的总索引。每轮专项细节见对应文档，
> 本文档提供全局视图：轮次脉络、修复要点、挂账清单与整体话术。
> 测试基线演进：129 → 134 → 145 → 147 → **173 全绿**（2 skipped 为需外部模型的 TestVector）。

| 轮次 | 主题 | 核心问题 | 提交 | 专项文档 |
|---|---|---|---|---|
| 第一轮 | 记忆：读侧闭环 | buildContext 零调用方 / fact 拼装断线 / task 层死层 | `27bcea6` `ea541b1` | [memory-review-fixes.md](memory-review-fixes.md) |
| 第二轮 | 记忆：结构性 | user 键是 chatId / ChatMemory 逐轮累积 / 覆盖面 | `b362dd7` | [memory-review-fixes.md](memory-review-fixes.md) |
| 第三轮 | 工作流：SSE | 伪流式 + 断连永久孤儿任务 | `6f4fb61` | 本文档（架构见 [architecture-agent-workflow.md](architecture-agent-workflow.md)） |
| 第四轮 | 检索：容错 | 共享 8 线程池 + 超时不取消底层任务 | `20286f7` | 本文档（架构见 [architecture-hybrid-retrieval.md](architecture-hybrid-retrieval.md)） |
| 第五轮 | 检索/RAG：正确性 | chat_id 割裂 / keyword 路失效 / 证据评审空转（修 3 挂 3） | `0104d07` | [retrieval-review-fixes.md](retrieval-review-fixes.md) |

一条主线贯穿五轮：**每一轮都打在"写了但没真正用起来"或"局部故障被放大成系统故障"这两类结构性缺陷上**，
而不是零散的 bug 修补。

---

## 第三轮：SSE 伪流式 + 断连永久孤儿任务（细节）

Review 原话要点：*"整个 ReAct 循环（4-6 次阻塞 LLM 调用）在 Flux.defer 里同步跑完才开始发帧；
Reactor 的 cancel 不是 error——用户关浏览器后 completeTask 永不执行，任务永久停在 RUNNING，
全项目没有任何孤儿任务回收。"*

### 问题 1：SSE 伪流式

- **现象**：`WorkflowReactAgentService.stream()` 把整个 ReAct 循环包在一个 `Flux.defer` 里，
  4-6 次阻塞 LLM 调用全部完成后才开始发帧——用户盯着空白等十几秒，"流式"只是分帧不是流式。
- **根因**：响应式外壳包着命令式内核，帧的**产生时机**仍由最慢的整条循环决定。
- **修法**：重写为**递归单步流** `stepFlux`——每步一包 `Flux.defer`（规划/执行完成即发 trace 帧），
  `concatWith(stepFlux(state, stepNum + 1))` 递归续步，成稿接 `finalAnswerFlux` 流式输出 token。
  帧序与原来完全一致，只有**时机**提前：单步完成即出帧。

### 问题 2：断连产生永久孤儿任务

- **现象**：用户关浏览器 → Reactor 发 CANCEL 信号 → `onErrorResume` 不触发（cancel 不是 error）
  → `completeTask` 永不执行 → 任务永久 RUNNING；全项目 `@Scheduled` 只有 memory/audit/rate-limit/ingestion，
  没有任何孤儿回收。
- **修法**（双保险）：
  1. **实时收尾**：`doFinally` 按 `SignalType` 分支——CANCEL 分支调 `AgentWorkflowEngine.abandonTask`
     （payload 带 `abandoned: true`），OUTCOME 记 `cancelled`；
  2. **守卫式 SQL**：`failIfNotTerminal` 用条件 UPDATE（`WHERE status NOT IN ('DONE','FAILED')`）
     返回影响行数——防"取消收尾"与"正常完成"竞态互相覆盖终态；
  3. **兜底回收**：`WorkflowTaskReclaimer` 每 5 分钟把非终态超 30 分钟的任务守卫式置 FAILED
     （覆盖断连漏网与进程重启遗留），计数 `agent.workflow.task.reclaimed`。
- **附带**：`ReactPlannerFallbacks` 抽取规划降级工具（497 → 489 行），换取 stream 重写的 500 行空间。

- **验证**：`WorkflowReactAgentServiceStreamTest`（帧序 / 两步递归 / `take(1)` 断连触发 abandonTask
  且不覆盖终态 / 异常单帧）；`WorkflowTaskReclaimerTest`（回收、cutoff、空跳过、守卫竞态、异常不外抛）。
  测试 134 → 145。

---

## 第四轮：共享线程池 + 不可取消的超时拖死四路检索（细节）

Review 原话要点：*"每请求占 4 任务，并发 2 个请求就开始排队；completeOnTimeout(3s) 不取消底层任务，
web 路 readTimeout 8s > 3s。pgvector 抖动一次 → 后续所有请求'3 秒后静默返回空' → 用户看到'知识库为空'，
无按路的失败计数。局部故障被伪装成系统性空结果。"*

### 修法（`HybridRetrievalService` 重写）

1. **专用线程池**：`ThreadPoolExecutor` 默认 16 线程（`app.retrieval.pool-size` 可配），
   与公共 ForkJoinPool 隔离；池观测 gauge（`retrieval.pool.active` / `queued`）；
2. **超时真取消**：独立单线程超时调度器，到点 `promise.complete(空)` 抢首次完成 +
   `worker.cancel(true)`（排队任务跳过、运行中任务中断）——区别于 `completeOnTimeout`
   的"只完成 future 不取消任务"，慢依赖不会在调用方放弃后继续占线程拖垮整池；
   worker 内 success/error 均以 `promise.complete(...)` 返回值守门，超时后不重复计数；
3. **底层超时对齐预算**：web 路连接/读取 1s/2.5s < 3s 单路预算，任务总能自行退出；
4. **降级显式可见**：按路计数 `retrieval.source.requests{source, outcome=success|error|timeout}`；
   结果携带 `degradedSources`，整体 outcome 四档 `success/empty/degraded/degraded-empty`
   ——"四路全降级导致的空"不会再被当成"知识库为空"。

- **验证**：`HybridRetrievalServiceTest` 重写 5 例（含 150ms 超时 + 永久阻塞路的中断验证
  `interrupted.await`、四路全堵的 degraded-empty 断言）。测试 145 → 147。

---

## 第五轮：检索/RAG 三条 P0（摘要，细节见专项文档）

1. **chat_id 硬过滤割裂知识库**：三路统一改租户级过滤 + 会话软作用域（`ChatScope`，同会话
   命中 +0.05 有界加分）——"当前会话的资料更相关"是排序信号，不是排除边界；
   DeepResearch 临时 chatId 不再必然空结果。
2. **keyword 路三重失效**：`LexicalMatcher`（CJK 2-gram 切词 + 查询分母召回分）三处共用
   （keyword 路 / 线上重排 / 证据判分）；候选池阈值放开 + 扩容；诚实定位"向量候选池上的词法重排"。
3. **证据评审空转**：判分结果真正决定生成上下文（降序组织、0.30 垃圾线剔除、截断 rerankTopK，
   失效降级回检索序）；入库补写 `created_at` 激活时效度。

---

## 全局挂账清单

| 侧 | 问题 | 修法方向 | 详见 |
|---|---|---|---|
| 检索 | ① 四路分数不同量纲直接加权（graph 恒 0.85 / web 排名分 / vector cosine），污染到"可信度 xx%"展示层 | RRF 融合（rank 免疫量纲）+ 展示层换语义 | [retrieval-review-fixes.md](retrieval-review-fixes.md) |
| 检索 | ③ 图谱路零数据生产端（无 kg_entity 写入代码，中文实体链接必空） | 实体链接 n-gram/别名匹配 + ingestion 自动抽取关联 | 同上 |
| 检索 | ⑥ 两套 RAG 管线漂移（线上 topK 6 vs 评测 12 条无预算直拼） | 线上切混合管线共享 core + 上下文字符预算 | 同上 |
| 检索 | VectorRetriever 无语句级超时（调用方 3s 取消已兜底） | `SET LOCAL statement_timeout` | — |
| 记忆 | advisor 记忆段未计 token 记账 / 召回无语义匹配 / 匿名画像合并 | advisor after() 回填 usage / 召回前向量过滤 / 登录后合并 | [memory-review-fixes.md](memory-review-fixes.md) |
| 工作流 | 单 Agent `ReactAgentService.stream` 仍伪流式（工作流版已修） | 复用第三轮 stepFlux 模式，需前端联调 | — |

---

## 面试话术（60 秒全程版）

> "这个项目我持续做了五轮 review 整改，每轮都是先核实、再修复、带回归测试，测试从 129 涨到 173 全绿。
> 前两轮是记忆子系统：先是读侧没闭环——召回逻辑写好了零调用方，fact 层查了复验了但拼装时漏了 append；
> 后是结构性问题——画像按 chatId 存换会话就丢，我换认证主体做 user 键；我第一版把记忆拼进 user prompt
> 会被 ChatMemory 逐轮重放，第 N 轮堆 N 份，改成 advisor 在请求组装期插独立 SystemMessage——
> 这是个通用模式：增强请求，但别污染存储。
> 第三轮修工作流 SSE：伪流式改递归单步流，断连的孤儿任务用 doFinally 识别 CANCEL + 守卫式 SQL 收尾
> 加定时回收双保险。第四轮修检索容错：超时从'只完成不取消'改成真取消，降级按路计数显式可见，
> 局部故障不再伪装成知识库为空。第五轮修检索正确性：chat_id 从硬过滤改成租户共享加软加成，
> 中文词面匹配 CJK 化，证据判分从只喂展示层改成真正决定生成上下文。
> 五轮下来我的最大收获是：review 打出来的问题很少是孤例，背后往往是同一类结构性缺陷——
> 写了没消费、局部故障全局化、信号当边界用。"

---

## 归属口径

- **个人独立完成**：记忆子系统（含两轮整改）、五轮整改中新增的组件
  （MemoryInjectionAdvisor / ChatScope / LexicalMatcher / WorkflowTaskReclaimer / ReactPlannerFallbacks 等）
  与全部修复实现。
- **上游既有设计**：混合检索四路架构、证据评审、工作流引擎、agent harness、双 RAG 管线——
  整改是在其上做问题定位与修复。
