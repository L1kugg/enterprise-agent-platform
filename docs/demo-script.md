# 可复现演示脚本

本脚本用于一场 5 到 8 分钟的本地演示，证明 KnowledgeOps Agent 是一个可部署的 Spring AI RAG 平台，而不是单接口示例。

## 1. 启动容器栈

```bash
./scripts/demo.sh
```

预期结果：

- 前端控制台：`http://localhost:8088`
- 后端 API：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- smoke test 输出 `e2e chat flow success`

## 2. 准备请求头

使用 `.env.example` 中的本地种子 API Key，或控制台「鉴权」卡片中的值。

```bash
export BASE_URL=http://localhost:8080
export API_KEY=<local-demo-api-key>
export TENANT_ID=tenant-acme
export CHAT_ID=heat-safety-demo
```

## 3. 上传演示 PDF

```bash
curl -X POST "$BASE_URL/ingestion/upload/$CHAT_ID" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -F "file=@demo-data/heat-safety-policy.pdf"
```

验证幂等性时，可使用自定义的非敏感幂等头值重复上传，并确认任务没有重复创建。

记录返回的 `jobId`。

```bash
curl "$BASE_URL/ingestion/jobs?chatId=$CHAT_ID&limit=5" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

如果本地 profile 关闭了队列 worker，可手动处理一个任务：

```bash
curl -X POST "$BASE_URL/ingestion/jobs/process?jobId=<jobId>" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 4. 执行演示问答

| 场景 | 问题或操作 | 预期信号 |
|---|---|---|
| PDF 上传 | 上传 `demo-data/heat-safety-policy.pdf` | 创建 ingestion 任务，可见队列后端与任务状态。 |
| 异步入库 | 查询 `/ingestion/jobs?chatId=heat-safety-demo` | 任务达到 `SUCCEEDED`，或暴露重试/错误详情。 |
| 检索命中 | `Summarize heat exposure control requirements.` | 回答涉及补水休息、轮换、恢复区与主管复核。 |
| 引用来源 | `Which source supports the no-fabrication rule?` | 回答包含如 `source=heat-safety-policy.pdf` 的引用。 |
| 证据片段 | 通过控制台 RAG 工作流提问 | 回答中可见引用标签与证据片段。 |
| 空结果兜底 | `What is the travel reimbursement policy?` | 助手回复当前知识库中没有匹配内容。 |
| 租户隔离 | 换一个 `X-Tenant-Id` 重复同一 RAG 问题 | 不返回跨租户的策略内容。 |
| 权限失败 | 不带有效鉴权调用受保护的管理路由 | 请求被拒绝，且可在审计日志中查到记录。 |

## 5. 验证运行时证据

```bash
./scripts/demo.sh verify
python3 scripts/run_regression.py \
  --dataset evaluation/dataset.large.json \
  --predictions evaluation/predictions.sample.json \
  --threshold 0.70 \
  --correctness-threshold 0.75 \
  --citation-hit-threshold 0.70
```

演示完成后的可用入口：

- API 健康检查：`http://localhost:8080/actuator/health`
- Prometheus 指标：`http://localhost:8080/actuator/prometheus`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- 运行日志：`./scripts/demo.sh logs`

## 6. 清理

```bash
./scripts/demo.sh down
```
