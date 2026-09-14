# 运维手册

## 1. 可观测性栈启动

```bash
docker compose -f docker-compose.observability.yml up -d
```

访问入口：

- Prometheus：`http://localhost:9090`
- Loki：`http://localhost:3100`
- Tempo：`http://localhost:3200`
- Alertmanager：`http://localhost:9093`

## 2. Grafana 仪表盘包

预置的 Grafana 仪表盘位于 `observability/grafana/dashboard.json`。

**导入方式：**
1. 打开 Grafana → Dashboards → Import
2. 上传 `dashboard.json` 或粘贴其内容
3. 选择 Prometheus 数据源
4. 点击 Import

**包含面板：**
- Request Rate / P95 Latency / Error Rate（HTTP 层）
- RAG Pipeline Latency（检索、重排、管线 p95）
- ReAct Stream Latency（总延迟、首 token p95）
- Ingestion Jobs（提交、成功、失败速率）
- Ingestion Duration P95
- JVM Heap Usage（含阈值告警）
- HikariCP Pool（活跃、空闲、等待连接数）
- Tool Query P95 by Tool

## 3. 核心告警

- `HighHttpP95Latency`：p95 > 1.5s 持续 5 分钟
- `IngestionFailureRateHigh`：入库失败率 > 5%

## 4. 队列后端模式

- Redis Stream：`APP_INGESTION_QUEUE_BACKEND=redis_stream`
- RabbitMQ：`APP_INGESTION_QUEUE_BACKEND=rabbitmq`
- DB 轮询兜底：`APP_INGESTION_QUEUE_BACKEND=db_polling`

终态失败任务进入 DLQ 流/队列。

## 5. 日志采集

- 应用日志文件：`logs/knowledgeops-agent.log`
- Promtail 抓取 `logs/*.log` 并推送到 Loki
- 链路与请求关联字段：`trace_id`、`request_id`、`chat_id`

## 6. 夜间回归评测

```bash
python3 scripts/generate_eval_dataset.py
python3 scripts/generate_eval_predictions.py
python3 scripts/run_regression.py --dataset evaluation/dataset.large.json --predictions evaluation/predictions.generated.json --threshold 0.75
```

## 7. 性能验证

```bash
k6 run performance/k6/chat_ingestion_load.js -e BASE_URL=http://localhost:8080
k6 run performance/k6/distributed_chat_ingestion.js -e BASE_URL=http://localhost:8080
python3 performance/k6/generate_report.py --summary reports/performance/distributed-k6-summary.json
```

## 8. 故障排查手册

1. 确认应用健康端点与各依赖可用性。
2. 检查入库队列堆积与失败任务。
3. 按 `trace_id` 串联关联日志。
4. 在 Prometheus 中查看 p95 延迟与错误率突增。
5. SLA 持续恶化时触发降级或回滚。
