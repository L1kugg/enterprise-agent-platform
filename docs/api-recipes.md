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

## 上传 PDF 进行摄取

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
