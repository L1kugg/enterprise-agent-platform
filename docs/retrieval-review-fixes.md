# 检索/RAG Review 整改记录

> 第五轮 review：六条检索/RAG 问题。本轮修复其中危害最大的三条（会话割裂 / keyword 路失效 / 证据评审空转），
> 另三条（量纲污染 / 图谱零生产端 / 管线漂移）挂账并给出修法方向。
> 修复提交前基线 147 测试全绿，修复后 173 全绿（+26 个回归测试）。

## 已修复

### 1. chat_id 硬过滤使知识库按会话割裂（原④，危害最大）

**现象**：A 会话上传的文档 B 会话检索不到；DeepResearch 用临时 chatId（`research_+taskId`）发检索，
向量/关键词两路必然为空，深研只剩 web 路。

**根因**：`VectorRetriever` / `KeywordRetriever` / 线上 `RagAnswerService` 三处的过滤表达式都是
`tenant_id == X && chat_id == Y` 硬边界——"会话"被当成了"知识库的作用域"，而知识库的真实作用域是租户。

**修法**：三处统一改为**租户级过滤 + 会话软作用域**（新增 `retrieval/ChatScope`）：
- 过滤表达式只含 `tenant_id`；知识库回归"租户内共享"语义；
- 同会话命中的文档获得有界加分 `+0.05`（封顶 1.0）——"当前会话上传的资料更可能相关"
  是排序信号，不是排除边界；跨会话/临时会话检索不再必空。

**验证**：`ChatScopeTest`（识别/加成/封顶）、`VectorRetrieverScoreTest.filterIsTenantScopedWithoutChatId`
与 `sameChatDocumentGetsBoundedBoost`、`KeywordRetrieverTest.filterIsTenantScopedWithoutChatId`
与 `sameChatDocumentGetsBoundedBoost`。

### 2. keyword 路三重失效（原②）

**现象**：中文查询必然空结果；长文档分数必然趋零；与向量路候选完全同源（不互补）。

**根因**（三重）：
1. `tokenize` 按非字母数字切分，中文无分隔符 → **整句成单 token**，"预约" 永远匹配不上 "课程预约怎么办理"；
2. 打分 `overlap / 文档 token 数` → 分母是文档长度，**长文档必然趋零**；
3. 候选来自与向量路同查询、同阈值 0.25 的同一次相似度检索——**只是语义序的重新洗牌**，不是独立召回。

**修法**（新增共用工具 `retrieval/LexicalMatcher`，keyword 路 / 线上重排 / 证据判分三处共用）：
1. **CJK 2-gram 切词**：中文连续段切字符 bigram（无分词器时中文检索的标准做法），拉丁/数字 token 整体保留；
2. **分母改为查询 token 数**（`recallScore`：查询词有多大比例命中目标，召回语义）；
3. **候选池放开**：租户级 + 相似度阈值 0（ACCEPT_ALL）+ 池扩到 `max(topK×4, 40)`——
   嵌入距离远但词面精确命中（术语、错误码）的文档也能入选；
4. 词面匹配基于**纯正文**（`getText()`）——`getFormattedContent()` 拼有元数据前缀，
   元数据词混入会造成"查 pdf 命中所有文档"这类噪声。

**诚实边界**：候选仍来自向量库（无独立倒排索引），本路定位是"向量候选池上的词法重排"；
独立 BM25 / pg 全文索引是演进方向（已写入架构文档）。

**验证**：`LexicalMatcherTest`（bigram/混合切词/分母契约）、`KeywordRetrieverTest`
（中文命中、长文档不稀释、会话加成、过滤断言）、`RagAnswerServiceRerankTest`（线上重排同款修复）。

### 3. EvidenceJudge 评审空转（原⑤）

**现象**：证据评审对答案质量零作用；时效度维度恒中性。

**根因**（两个断点）：
1. `HybridRagAnswerService.buildContext(retrievedDocs)`——喂给 LLM 的上下文用的是**原始检索序**，
   判分结果只进了展示层（citations）与 fact 记忆，排序/过滤完全没被消费；
2. ingestion 只写 6 个元数据键、**从不写时间戳** → timeliness 恒 0.70 中性，"新文档更可信"的信息全部丢失。

**修法**：
1. **判分消费**（`selectContextDocs`）：按综合分降序组织上下文、剔除低于垃圾线（0.30）的证据、
   截断到 `rerankTopK` 进入 prompt；证据与原文档按 `sourceType|chunkId` 关联
   （`EvidenceItem` 是对外契约结构，不加内部字段）。降级语义：判分为空退回检索序、
   全部低于垃圾线退回判分序头部——评审失效不阻塞管线，不用假"知识库为空"糊弄用户；
2. **入库写 `created_at`**（epoch 毫秒）：时效度真正激活（30 天内 1.0 → 一年以上 0.5）；
3. 判分相关性加成的切词同步接 `LexicalMatcher`（中文查询的命中检查不再落空）。

**验证**：`HybridRagAnswerServiceJudgingTest`（判分序进上下文/垃圾线剔除/两类降级）、
`EvidenceJudgeServiceTest`（时效度三档 + 中文命中加成 + 排序）、
`IngestionServiceTest.chunkMetadataCarriesScopeKeysAndIngestTimestamp`（元数据契约含 created_at）。

## 挂账（本轮不修，已评估）

| 问题 | 现状与危害 | 修法方向 | 工作量 |
|---|---|---|---|
| ① 四路分数不同量纲直接加权 | graph 恒 0.85、web 是排名衰减分、vector 是 cosine，`×权重` 后直接比大小，污染一路泄漏到"可信度 xx%"展示层 | 融合层改 RRF（`Σ w/(k+rank)`，k=60，rank 免疫量纲）；展示层"可信度"同步换语义 | 中 |
| ③ 图谱路零数据生产端 | 全仓无 kg_entity 写入代码，仅 7 条 'public' 种子；实体链接=最长 token LIKE，中文必空 | 实体链接改别名/n-gram 多词匹配；ingestion 完成后按别名表自动抽取实体关联（规则先行，LLM 抽取作演进） | 中大 |
| ⑥ 两套 RAG 管线漂移 | 线上 /ai/pdf/chat 用简单版（topK 6），评测用混合版（12 条全文直拼无预算）——评测度量的不是线上管线 | 线上切到混合管线（共享 core），同 topK + 上下文字符预算 | 中 |

## 30 秒面试话术

> 这轮 review 打在检索侧最痛的三点。第一，chat_id 被当成了知识库作用域做硬过滤，跨会话和深研的临时会话
> 检索必空——我改成租户共享 + 会话有界加分，把"相关性信号"和"访问边界"分开。第二，关键词路对中文是死的：
> 整句单 token、分母是文档长度、候选和向量路同源——我抽了个 CJK 2-gram 的词面匹配工具三处复用，
> 并在文档里诚实写明它仍是"向量候选池上的重排"，独立 BM25 才是彻底解。第三，证据评审是个空转的规则器：
> 判分结果根本没进生成上下文，时效度因为入库不写时间戳恒中性——我让判分真正决定上下文的排序、
> 剔除和截断，并补上入库时间戳。三条都带了回归测试，147 到 173 全绿。

## 归属口径

- 混合检索四路架构、证据评审、双管线为上游既有设计；本轮工作是在其上做**问题定位、修复方案设计与实现**
  （ChatScope / LexicalMatcher 两个共用工具为本轮新增）。
- 记忆子系统（advisor 注入、四层模型）见 `docs/memory-review-fixes.md`，为个人独立完成。
