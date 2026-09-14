# API 示例

以下示例假设本地 Docker Compose 服务栈已运行在 `http://localhost:8080`。

## 通用请求头

```bash
export BASE_URL=http://localhost:8080
export API_KEY=<local-demo-api-key>
export TENANT_ID=default
```

大多数本地示例可直接使用预置的 API Key：

```bash
-H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
```

如需基于 JWT 的调用，请将 `API_KEY` 设置为 `.env.example` 中的开发预置值，并先用 API Key 换取 JWT：

```bash
curl -X POST "$BASE_URL/auth/token" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 对话

```bash
curl "$BASE_URL/ai/chat?prompt=Summarize%20KnowledgeOps%20Agent&chatId=demo-chat" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

可选查询参数：

- `modelProfile=economy`
- `modelProfile=balanced`
- `modelProfile=quality`

## 上传 PDF 用于入库

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

列出某个会话最近的入库任务：

```bash
curl "$BASE_URL/ingestion/jobs?chatId=demo-rag&limit=20" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 调用 PDF RAG 问答接口

```bash
curl "$BASE_URL/ai/pdf/chat?prompt=What%20are%20the%20key%20points%3F&chatId=demo-rag" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

当存在匹配的知识来源时，响应中会包含答案文本与引用信息。

## ReAct 智能体

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

## 健康检查与可观测

```bash
curl "$BASE_URL/actuator/health"
curl "$BASE_URL/actuator/prometheus"
```

日志、链路追踪、告警与演练流程请继续阅读[运维手册](operations.md)。
