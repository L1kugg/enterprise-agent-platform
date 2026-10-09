# 可复现演示脚本

本脚本面向 5 到 8 分钟的本地演示，用于证明 Enterprise Agent Platform 是一个可部署的 Spring AI RAG 平台，而不是单端点演示。

## 1. 启动技术栈

```bash
./scripts/demo.sh
```

预期结果：

- 前端控制台：`http://localhost:8088`
- 后端 API：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- 冒烟测试输出 `e2e chat flow success`

## 2. 准备请求头

使用 `.env.example` 或控制台认证卡片中预置的本地 API Key。

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

如需验证幂等性，可使用自己的非敏感幂等请求头值重复上传，并确认任务没有重复创建。

记录返回的 `jobId`。

```bash
curl "$BASE_URL/ingestion/jobs?chatId=$CHAT_ID&limit=5" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

如果本地 profile 中禁用了队列 worker，可以手动处理一个任务：

```bash
curl -X POST "$BASE_URL/ingestion/jobs/process?jobId=<jobId>" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## 4. 执行演示问题

| 场景 | 问题或操作 | 预期信号 |
|---|---|---|
| PDF 上传 | 上传 `demo-data/heat-safety-policy.pdf` | 摄取任务创建成功，包含队列后端和任务状态。 |
| 异步摄取 | 查询 `/ingestion/jobs?chatId=heat-safety-demo` | 任务达到 `SUCCEEDED`，或暴露重试/错误详情。 |
| 检索命中 | `Summarize heat exposure control requirements.` | 回答涉及补水休息、轮岗、恢复区域和主管复核。 |
| 引用来源 | `Which source supports the no-fabrication rule?` | 回答包含引用，例如 `source=heat-safety-policy.pdf`。 |
| 证据片段 | 通过控制台 RAG 工作流提问 | 回答中可见引用标签和证据片段。 |
| 空结果兜底 | `What is the travel reimbursement policy?` | 助手提示当前知识库没有匹配内容。 |
| 租户隔离 | 使用另一个 `X-Tenant-Id` 重复同一个 RAG 问题 | 不返回跨租户的策略内容。 |
| 权限失败 | 不带有效认证调用受保护的管理路由 | 请求被拒绝，并可通过审计日志核查。 |

## 5. 核验运行时证据

```bash
./scripts/demo.sh verify
python3 scripts/run_regression.py \
  --dataset evaluation/dataset.large.json \
  --predictions evaluation/predictions.sample.json \
  --threshold 0.70 \
  --correctness-threshold 0.75 \
  --citation-hit-threshold 0.70
```

演示结束后可用的观测入口：

- API 健康检查：`http://localhost:8080/actuator/health`
- Prometheus 指标：`http://localhost:8080/actuator/prometheus`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- 运行时日志：`./scripts/demo.sh logs`

## 6. 清理环境

```bash
./scripts/demo.sh down
```
