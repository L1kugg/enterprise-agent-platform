# 记忆子系统 Review 问题整改记录

> 记录记忆子系统（`memory/`）两轮 code review 发现的问题、根因、修法与验证，
> 对应提交：第一轮 `27bcea6` + `ea541b1`，第二轮 `b362dd7`。
> 配套设计文档见 [architecture-memory-system.md](architecture-memory-system.md)；
> 五轮整改总览见 [review-fixes-overview.md](review-fixes-overview.md)。

## 背景

记忆子系统采用四层模型：short（24h 会话要点）/ long（永久画像）/ task（30 天任务结论）/ fact（租户级高置信事实），
写入侧四个记录器 + 异步画像提取器早已就位。两轮 review 集中在同一个主题：**写入侧闭环了，读侧（召回注入）没有真正跑起来**。

---

## 第一轮：读侧闭环的三个断点

Review 原话：*"为什么 buildContext 没接进任何用户链路、fact 层断在拼装前一步、task 层有写无读。"*

### 问题 1：buildContext 零生产调用方

- **现象**：`MemoryService.buildContext()` 召回逻辑完整（分层查询 + fact 复验 + USE 事件留痕），
  但没有任何用户链路调用它 —— 记忆只写不读，用户感知不到记忆的存在。
- **根因**：读侧作为服务层能力实现后，没有做链路接入这一步。
- **修法**：
  - `27bcea6`：先接评测链路 HybridRagAnswerService（先例实现）+ `/ai/memory` REST 端点（query/save/events，补 README 承诺）；
    buildContext 补 USE 事件，每条记忆从写入到召回可在 memory_event 追溯；召回失败降级为无记忆，不中断管线。
  - `ea541b1`：接入用户可见的 chat（ChatController）与 react（ReactAgentService）链路；
    react 循环外召回一次（防每步重复召回产生多次 USE 事件），全量三层注入规划与成稿 prompt，
    `finalizeResponse` 回填 `memoryUsed` 并写回 short 记忆（读写双侧闭环）。
- **验证**：HybridRagAnswerServiceMemoryTest / ChatControllerMemoryTest / ReactAgentServiceTest
  断言记忆进入 prompt、memoryUsed 上报、召回失败降级。

### 问题 2：fact 层断在拼装前一步

- **现象**：buildContext 对 fact 做了查询、置信度复验、USE 事件留痕，三步全做了，
  但拼装 contextText 时**漏了 append fact 分区** —— 干完活把结果扔了。
- **根因**：分层拼装代码只写了 short/long 两段，fact 逻辑是后补的，补在了查询侧而非拼装侧。
- **修法**：`ea541b1` 补上 fact 第三分区拼装；测试断言从"查了"升级为"拼进了最终上下文"的契约级断言。
- **验证**：MemoryServiceTenantIsolationTest 断言 contextText 含 fact 内容。
- **教训**：留痕（USE 事件）看起来像"用过了"，但留痕 ≠ 注入 —— 观测点在上游，实际消费点在下游，两者之间断掉不留痕侧痕迹。

### 问题 3：task 层有写无读（第一轮遗留）

- **现象**：`queryTaskMemory` 零生产调用方，任务结论写进去了没有任何链路读。
- **第一轮处置**：仅在 save 端点支持 task 类型写入，读侧未解决 → 遗留到第二轮（见问题 5）。

---

## 第二轮：三个结构性问题

Review 原话要点：*"①'跨会话'名不副实：long 层写入时 userId = chatId，新会话召不回上一会话攒的画像；②task 层仍是死层；③覆盖面不全：workflow v2 / DeepResearch / RagAnswerService 三条链路不注入，且 enriched 消息会被 ChatMemory 存进历史逐轮重放累积。"*

### 问题 4：「跨会话」名不副实 —— user 键是 chatId，画像随会话陪葬

- **现象**：注释宣称"召回跨会话记忆（long/fact）"，mapper 是 `WHERE user_id = #{userId}`，
  而 long 层（画像）写入时 userId = chatId —— 新会话 = 新 chatId = 召不回上一会话攒的用户画像。
  真正跨会话的只有 fact（租户级）。画像本该是最有价值的跨会话记忆，实际随会话陪葬。
- **根因**：系统有认证体系（ApiKeyOrJwtAuthFilter），记忆子系统却拿会话 ID 当用户 ID，
  把"会话"和"人"两个概念混用了。
- **修法**（`b362dd7`）：
  - 新增 `security/UserContext.currentUserId(fallback)`：从 SecurityContextHolder 取认证主体，
    匿名 / 异步线程（SecurityContext 为空）/ anonymousUser 回落 fallback（chatId）；
  - 五处接线全部换 user 键：ChatController / ReactAgentService / RagAnswerService / CustomerServiceController /
    ChatTurnMemoryRecorder / MemoryExtractionService（去重与写入都对齐 user 键）；
  - source 字段保留 `chat:{chatId}` 溯源 —— 记忆来自哪个会话仍可查。
- **效果**：画像按人存，换会话可召回；只有纯匿名访问才回落 chatId，向后兼容。
- **验证**：ChatControllerMemoryTest 断言 recordTurn/submitAsync 收到 user 键；
  MemoryExtractionServiceTest 断言画像去重与写入按 user 键。

### 问题 5：task 层仍是死层 —— 接两个消费方

- **修法**（`b362dd7`）：
  - **生产读路径**：`DeepResearchService.recallPriorFindings()` 召回租户内最近 5 条任务结论
    （`MemoryService.queryRecentTaskMemories`，复用 findByTypeAndConfidence 查询，task 写入 confidence 0.9 ≥ 0.7 门槛），
    注入 ResearchPlannerAgent 拆题 prompt —— "早前研究已得出的结论不重复拆题"；失败/为空降级空串，不影响研究主链路；
  - **API 消费方**：`GET /ai/memory/task/{taskId}` 按任务精确召回，供任务详情页 / 重跑前查看"上次研究到什么"。
- **验证**：MemoryControllerTest 断言按当前租户 + taskId 查询。

### 问题 6a：enriched 消息被 ChatMemory 持久化，会话内逐轮累积（第一版方案的隐患）

- **现象**：第一轮 chat 链路把记忆拼进 user 消息文本。该链路挂了 MessageChatMemoryAdvisor：
  调用后把本轮 user/assistant 消息 add 进 MysqlChatMemory —— 拼了记忆的 user 消息被存进历史，
  下一轮重放历史时旧记忆段又进 prompt，加上本轮新注入的记忆段 → **第 N 轮 prompt 里堆 N 份记忆**，token 持续膨胀。
- **根因**：增强（enrich）了发给模型的请求，也污染了被持久化的存储 —— 没有区分"请求视图"与"存储视图"。
- **修法**（`b362dd7`）：新增 `memory/MemoryInjectionAdvisor`（Spring AI 1.1.7 `BaseAdvisor`），
  在**请求组装期**（before）把记忆快照作为独立 SystemMessage 插入 prompt 首部：
  - 不改写 user 消息文本 → MessageChatMemoryAdvisor 持久化的仍是原始对话；
  - MysqlChatMemory.add 只存显式 add 的消息，system 消息不落历史 → 零累积；
  - 每次调用重新召回 → 注入的永远是最新的唯一一份；
  - **显式 opt-in**：调用方传 advisor 参数 `memory.tenantId` + `memory.userId` 才注入，
    未传参的链路（react / 评测 / 画像提取等自行管理记忆的调用）原样透传零影响；
  - 任何召回异常静默透传，绝不影响生成。
- **验证**：MemoryInjectionAdvisorTest 五用例 —— system 消息首插且 user 原文不动、includeShort 透传、
  未传参透传、召回失败降级、空键/空快照透传；ChatControllerMemoryTest 断言 user 消息 == 用户原文。

### 问题 6b：覆盖面不全 —— 四条链路补齐

- **修法**（`b362dd7`）：

| 链路 | 注入方式 |
|---|---|
| chat（/ai/chat）、客服（/ai/service） | chatClient / serviceChatClient 默认 advisor |
| PDF RAG（/ai/pdf/chat） | RagAnswerService 生成调用传 advisor 参数 |
| 工作流 ReAct v2 | callModel / callModelStream 传参（匿名空键自动透传不注入） |
| 深度研究（/ai/research） | priorFindings 注入拆题（task 层，即问题 5） |

- 至此 7 条生成链路（普通聊天 / 客服 / 简单 RAG / 混合 RAG 评测 / 单 Agent ReAct / 工作流 ReAct / 深度研究）记忆读写全通。

---

## 当前状态与验证

- 测试：129 → **134 全绿**（0 failures / 0 errors / 2 skipped）；Checkstyle 通过（WorkflowReactAgentService 497/500 行）。
- 各链路注入现状表见 [architecture-memory-system.md](architecture-memory-system.md#各链路注入现状读侧闭环)。

## 已知瑕疵与演进方向

| 瑕疵 | 说明 | 演进方向 |
|---|---|---|
| advisor 记忆段未计入 token 记账 | chat 链路记账按原始 prompt，advisor 注入的 system 段漏计（与评测链路同款低估） | advisor 的 after() 回填真实 usage |
| 召回侧无语义匹配 | 按时间倒序取最近 N 条，无 embedding 相似度门槛 | 召回前对 query 做向量过滤 |
| 匿名用户画像按 chatId 存 | 无认证环境下仍随会话陪葬（受限于无身份可用） | 前端引导登录后合并记忆 |

## 面试话术（30 秒版）

> "记忆子系统我做了两轮 review 整改。第一轮修读侧闭环：buildContext 写好了却零调用方，fact 层查了、复验了、留痕了，但拼装时漏了 append —— 干完活把结果扔了；我把它接进全部生成链路并补上观测。
>
> 第二轮更有意思，review 打回来三个结构性问题。最关键的是 user 键错了：画像按 chatId 存，换会话就召不回，等于白攒 —— 我用 UserContext 从 SecurityContext 解析认证主体，五处调用方统一换键，匿名回落 chatId 兼容。
>
> 累积问题是我自己第一版方案埋的：把记忆拼进 user prompt，会被 MessageChatMemoryAdvisor 存进历史并逐轮重放，第 N 轮堆 N 份。我读 Spring AI 源码发现 ChatMemory 只持久化显式 add 的 user/assistant 消息，所以改成 advisor 在请求组装期插独立 SystemMessage —— 每次召回最新一份、历史永远干净。这也是个通用模式：**增强请求，但别污染存储**。
>
> 注入设计成显式 opt-in 是有意的：advisor 不传参就透传，react、评测这些自行管理记忆的链路零影响；召回失败一律静默降级，记忆子系统永远不能拖垮生成主链路。"

归属口径：记忆子系统（含本轮全部整改）为个人独立完成；混合检索、工作流引擎、agent harness 为上游设计，记忆注入点是其预留的调用位置。
