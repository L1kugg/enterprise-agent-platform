# DeepResearch 深度研究测试手册

> 配套知识文档：`demo-data/deepresearch-test-knowledge.md`（企业级 RAG 平台技术选型评估报告）
> 适用版本：异步受理版 DeepResearch（POST 返回 202 + taskId，轮询取报告）

## 测试目标

验证深度研究全链路：**拆题 → 四路混合检索（向量/关键词/图谱/Web）→ 证据评分 → 报告生成**，
并确认异步受理、租户隔离、事件溯源三块基建工作正常。

## 一、准备：启动环境与入库知识文档

### 1. 启动全栈

```bash
cd <project-root>
./scripts/demo.sh
```

等待健康检查通过（后端 `http://localhost:8080`，前端 `http://localhost:8088`）。

### 2. 入库测试文档

用异步入库接口（推荐，能同时验证 ingestion 链路）：

```bash
API_KEY=<查看 .env 中的管理员 key>

# 提交异步入库任务
curl -s -X POST "http://localhost:8080/ingestion/upload/deepresearch-test-01" \
  -H "X-API-Key: ${API_KEY}" \
  -F "file=@demo-data/deepresearch-test-knowledge.md"

# 轮询任务状态直到 success（返回体里有 jobId）
curl -s "http://localhost:8080/ingestion/jobs/<jobId>" -H "X-API-Key: ${API_KEY}"
```

入库成功后，向量路、关键词路立即有数据；**图谱路**由 GraphExtractionService
异步抽取实体（Keystone、Compass、Atlas、pgvector、Milvus 等会作为实体写入当前租户），
建议等 30~60 秒再发起研究，给图谱抽取留时间。

## 二、发起深度研究

```bash
# 异步受理：立即返回 202 + taskId（status=PLANNING, report 为空）
curl -s -X POST "http://localhost:8080/ai/research/tasks" \
  -H "X-API-Key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"topic": "对比各RAG候选方案的检索命中率、幻觉率与三年TCO，给出推荐结论"}'
```

**推荐测试主题（按难度分层，由易到难）：**

**L1 直接检索（基线，验证链路通）：**

| # | 主题 | 验证点 |
|---|------|--------|
| 1 | 对比各RAG候选方案的检索命中率、幻觉率与三年TCO，给出推荐结论 | 数字类事实（96.0%、6.5%、102 万）是否被准确引用 |
| 2 | Keystone 方案的技术架构与性能表现 | 图谱实体（Keystone/pgvector）是否参与证据 |

**L2 同义改写（考向量语义，问题不含文档原词）：**

| # | 主题 | 评分答案 |
|---|------|---------|
| 3 | 哪个平台最容易一本正经地胡说八道 | Forge，幻觉率 24% |
| 4 | 有没有方案因为数据不能放在自己手里被毙掉的 | Atlas，合规一票否决 |
| 5 | 公司最后选中的方案，养它三年要花多少钱 | Keystone，三年 TCO 约 102 万元 |

**L3 约束推理（答案文中没有现成的，须比对多处数字推导）：**

| # | 主题 | 评分答案 |
|---|------|---------|
| 6 | 如果公司规定每年基础设施预算不得超过25万元，哪些方案直接出局，为什么 | Atlas（76.6万/年）出局；Keystone（21万）与 Compass（19万）存活 |
| 7 | 响应速度最快的方案为什么最后没被选上 | Atlas P95 420ms 最快，但数据上云不满足本地化合规，被一票否决 |

**L4 多跳汇总与陷阱：**

| # | 主题 | 评分答案 |
|---|------|---------|
| 8 | 评估期间四个方案分别暴露过什么问题，哪个性质最严重 | 需收齐：Keystone 图谱噪声、Compass 跨租户泄露×2 + Milvus 脑裂、Atlas 合规、Forge 幻觉率 24%；严重性排序合理即可 |
| 9 | 文档评估了哪些少数民族语言的检索效果，结果如何 | **陷阱题**：文档只说"未测试、二期补充"。理想回答明说无数据；若编造"藏语准确率 xx%"即为幻觉实锤 |

L3/L4 是真正有区分度的用例：拆题不再送分、关键词路失灵、报告须综合推理。若 L1 全过而 L3/L4 答错，说明检索可用但证据综合是短板。

## 三、验证执行过程

### 1. 轮询任务状态

```bash
TASK_ID=<受理响应里的 taskId>

# 观察状态流转：PLANNING → SEARCHING → RETRIEVING → WRITING → DONE
curl -s "http://localhost:8080/ai/research/tasks/${TASK_ID}" -H "X-API-Key: ${API_KEY}"
```

### 2. 查看事件溯源

```bash
curl -s "http://localhost:8080/ai/research/tasks/${TASK_ID}/events" -H "X-API-Key: ${API_KEY}"
```

预期事件序列：`TASK_CREATED → STATE_CHANGED(PLANNING) → STEP_STARTED(ResearchPlanner) →
STEP_COMPLETED → STATE_CHANGED(SEARCHING/RETRIEVING) → STEP_STARTED(RagResearchAgent)... →
STEP_COMPLETED(ReportWriter) → TASK_DONE`。

### 3. 获取最终报告

```bash
curl -s "http://localhost:8080/ai/research/tasks/${TASK_ID}/report" -H "X-API-Key: ${API_KEY}"
```

## 四、结果评估清单

| 检查项 | 通过标准 |
|--------|---------|
| 异步受理 | POST 后 2 秒内返回 202 + taskId |
| 状态流转 | 全程可见 PLANNING→...→DONE，无卡死 |
| 子问题数量 | 拆题产出 3~5 个（当前为 prompt 约束，观察值） |
| 证据命中（L1） | 报告引用了文档中的具体数字（96.0% / 6.5% / 102 万元） |
| 语义检索（L2） | 同义改写问题仍能答对，不依赖原词命中 |
| 推理综合（L3） | 预算约束题答"Atlas 出局"；最快方案题答"Atlas 被合规否决" |
| 幻觉守卫（L4） | 陷阱题明确说"文档未测试、计划二期补充"，不编造数字 |
| 图谱参与 | 主题 2 的事件/步骤中可见图谱来源证据（sourceType=graph） |
| 步骤留痕 | 每个 RagResearchAgent 步骤的 docsFound > 0（L1~L3） |
| 队列拒绝（可选） | 将 worker-count 调到 1、queue-capacity 调到 1，第三次提交返回 429 |

## 五、指标观测（可选）

研究执行期间观察 Prometheus 指标：

```
research.pool.active / research.pool.queued   # 后台 worker 池水位
research.queue.wait                            # 受理到执行的排队时长
retrieval.source.requests{source=..., outcome=...}   # 四路检索成败/超时/饱和
retrieval.queue.wait{source=...}              # 检索任务出队前等待
rag.hybrid.pipeline.latency{outcome=...}      # 混合检索整体延迟
agent.workflow.step.latency{agent=...}        # 各 Agent 步骤延迟
```

Grafana 导入 `observability/grafana/dashboard.json` 后可直接看曲线。

## 六、常见排障

| 现象 | 原因与处理 |
|------|-----------|
| 报告为空且状态停在 PLANNING | 入库未完成或拆题 LLM 失败；查 events 里 ResearchPlanner 步骤 |
| 四路全部 degraded | pgvector/MySQL 未就绪；查 retrieval.source.requests 的 outcome 标签 |
| 图谱路无结果 | 图谱抽取未完成或抽取开关关闭（`app.graph.*` 配置）；确认 60 秒后再试 |
| 提交返回 429 | 研究队列打满，属预期行为；等在途任务完成或调大 queue-capacity |
| 状态卡非终态超过 30 分钟 | WorkflowTaskReclaimer 会自动收尾为 FAILED；也可重启触发 StartupSweeper |
