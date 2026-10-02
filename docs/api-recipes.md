# API 使用示例

以下示例假定本地 Docker Compose 技术栈已在 `http://localhost:8080` 上运行。

## 通用请求头

```bash
export BASE_URL=http://localhost:8080
export API_KEY=<local-demo-api-key>
export TENANT_ID=default
```

大多数本地示例可以直接使用预置的 API Key：

```bash
-H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
```

启用安全机制后，以 API Key 或 JWT 对应的租户为准，租户请求头不能用于切换授权范围。这些示例中保留该请求头只是为了本地演示关联，在换取 token 时必须与 API Key 一致。

对于基于 JWT 的调用，请先将 `API_KEY` 设为 `.env.example` 中的预置开发值，再用 API Key 换取 token：

```bash
curl -X POST "$BASE_URL/auth/token" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 聊天

```bash
curl "$BASE_URL/ai/chat?prompt=Summarize%20KnowledgeOps%20Agent&chatId=demo-chat" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

可选查询参数：

- `modelProfile=economy`
- `modelProfile=balanced`
- `modelProfile=quality`

## 上传文档进行摄取（PDF / Word / Markdown）

支持 `.pdf`、`.doc`、`.docx`、`.md` 四种格式，服务端按后缀白名单 + 文件头魔数校验：

```bash
curl -X POST "$BASE_URL/ingestion/upload/demo-rag" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "X-Idempotency-Key: demo-rag-001" \
  -F "file=@demo-data/heat-safety-policy.pdf"
```

查看返回的 `jobId`：

```bash
curl "$BASE_URL/ingestion/jobs/<jobId>" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

列出某个聊天最近的摄取任务：

```bash
curl "$BASE_URL/ingestion/jobs?chatId=demo-rag&limit=20" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 知识库文档清单与试搜

按租户列出文档清单（每份文档取最新入库任务，支持按文件名/批次搜索）：

```bash
curl "$BASE_URL/ingestion/documents?page=1&pageSize=20&search=heat" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

试搜：只走向量/关键词/图谱三条本地检索路返回原始命中片段，不调 LLM、不出答案，用于上传后快速验证「库里的东西搜不搜得到」：

```bash
curl "$BASE_URL/ingestion/search?q=hot%20work%20permit&topK=6" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

删除一份文档（级联清理向量切片 → 磁盘原文件 → 任务记录，任一步失败即中止）：

```bash
curl -X DELETE "$BASE_URL/ingestion/documents/demo-rag" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 会话管理

列出会话、查看单个会话（含分支结构），以及重命名（`PUT` 全量保存，`title` 字段即新标题）：

```bash
curl "$BASE_URL/ai/sessions" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"

curl "$BASE_URL/ai/sessions/<sessionId>" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"

curl -X PUT "$BASE_URL/ai/sessions/<sessionId>" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"title":"新标题","branches":[]}'
```

## 评测 Studio

评测四层数据（数据集 → 用例 → 运行 → 结果）的完整闭环：

```bash
# 创建评测集（内嵌用例；也可后续单独维护）
curl -X POST "$BASE_URL/ai/evaluation/datasets" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"name":"热作业安全冒烟集","cases":[{"caseId":"case-001","question":"动火作业前需要什么许可?","expectedKeywords":["动火作业许可"]}]}'

# 列出评测集 / 删除评测集（同一事务内级联清理结果 → 运行 → 用例 → 数据集，回执带各层条数）
curl "$BASE_URL/ai/evaluation/datasets" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
curl -X DELETE "$BASE_URL/ai/evaluation/datasets/<datasetId>" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"

# 触发一轮评测（逐题真实调用 RAG 问答后打分落库）
curl -X POST "$BASE_URL/ai/evaluation/datasets/<datasetId>/runs" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"modelProfile":"balanced"}'

# 查看运行明细 / 设为基线 / 基线与当前对比 / 导出 Markdown 报告
curl "$BASE_URL/ai/evaluation/runs/<runId>" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
curl -X POST "$BASE_URL/ai/evaluation/runs/<runId>/baseline" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
curl "$BASE_URL/ai/evaluation/datasets/<datasetId>/comparison" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
curl "$BASE_URL/ai/evaluation/runs/<runId>/report" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
```

## 用量统计

```bash
# 当月汇总：预算、消耗、Token、是否超限
curl "$BASE_URL/cost/summary" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"

# 本月按天趋势（请求/Token/成本，缺天补零）
curl "$BASE_URL/cost/trend" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"

# 调整月度预算
curl -X POST "$BASE_URL/cost/budget" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"monthlyBudgetUsd":50,"hardLimitEnabled":true}'
```

## 管理员文档总览（仅 ADMIN）

跨租户汇总全部文档与最新入库任务状态，并可远程清理任意租户的文档：

```bash
curl "$BASE_URL/admin/documents" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"

curl -X DELETE "$BASE_URL/admin/documents/<tenantId>/<chatId>" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
```

## 向 PDF RAG 端点提问

```bash
curl "$BASE_URL/ai/pdf/chat?prompt=What%20are%20the%20key%20points%3F&chatId=demo-rag" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

当存在匹配的知识来源时，响应中会包含回答文本和引用行。

## ReAct Agent

JSON 响应：

```bash
curl -X POST "$BASE_URL/ai/react/chat" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"prompt":"Find the next useful operations check","chatId":"react-demo","modelProfile":"balanced"}'
```

SSE 流式响应：

```bash
curl -N -X POST "$BASE_URL/ai/react/chat/stream" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"prompt":"Explain the ingestion pipeline","chatId":"react-stream-demo"}'
```

## 深度研究（异步受理 + 轮询）

`POST /ai/research/tasks` 受理即返回 **202 + taskId**（研究在后台执行，约 1-3 分钟），
报告不在创建响应里：

```bash
curl -X POST "$BASE_URL/ai/research/tasks" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"topic":"企业知识库检索链路调研","modelProfile":"balanced"}'
# 202 {"taskId":"task-xxxx","topic":"...","report":null,"status":"PLANNING"}
```

轮询单任务状态至终态（DONE / FAILED），完成后取报告：

```bash
# 状态轮询（含步骤与事件，3 秒一次足够）
curl "$BASE_URL/ai/research/tasks/task-xxxx" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"

# 报告（status=DONE 后调用；任务不存在 404）
curl "$BASE_URL/ai/research/tasks/task-xxxx/report" \
  -H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
# {"taskId":"task-xxxx","report":"# 研究报告 ..."}
```

队列打满时创建返回 **429** + `{"ok":0,"msg":"深度研究任务队列已满..."}`，稍后重试即可；
后端池容量可调（`APP_RESEARCH_WORKER_COUNT` / `APP_RESEARCH_QUEUE_CAPACITY`）。

## 历史与审计

```bash
curl "$BASE_URL/ai/history/chat" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"

curl "$BASE_URL/audit/logs" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 健康检查与可观测性

```bash
curl "$BASE_URL/actuator/health"
curl "$BASE_URL/actuator/prometheus"
```

关于日志、链路追踪、告警和演练工作流，请继续阅读[运维手册](operations.md)。
