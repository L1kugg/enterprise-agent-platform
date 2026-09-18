# 运维指南

## 1. 启动可观测性技术栈

```bash
docker compose -f docker-compose.observability.yml up -d
```

访问入口：

- Prometheus: `http://localhost:9090`
- Loki: `http://localhost:3100`
- Tempo: `http://localhost:3200`
- Alertmanager: `http://localhost:9093`

## 2. Grafana 仪表盘套件

预置的 Grafana 仪表盘位于 `observability/grafana/dashboard.json`。

**导入方式：**
1. 打开 Grafana → Dashboards → Import
2. 上传 `dashboard.json` 或粘贴其内容
3. 选择你的 Prometheus 数据源
4. 点击 Import

**包含面板：**
- Request Rate / P95 Latency / Error Rate（HTTP 层）
- RAG Pipeline Latency（检索、重排、管线 p95）
- ReAct Stream Latency（总延迟、首 token p95）
- Ingestion Jobs（提交、成功、失败比率）
- Ingestion Duration P95（摄取时长 P95）
- JVM Heap Usage（含阈值告警）
- HikariCP Pool（活跃、空闲、等待中的连接）
- Tool Query P95 by Tool（按工具统计的查询 P95）

## 3. 核心告警

- `HighHttpP95Latency`：p95 > 1.5s 持续 5 分钟
- `IngestionFailureRateHigh`：摄取失败率 > 5%

## 4. 队列后端模式

- Redis Stream：`APP_INGESTION_QUEUE_BACKEND=redis_stream`
- RabbitMQ：`APP_INGESTION_QUEUE_BACKEND=rabbitmq`
- 数据库轮询兜底：`APP_INGESTION_QUEUE_BACKEND=db_polling`

当 `APP_INGESTION_WORKER_ENABLED=true`（默认值）时，所有后端都会自动消费任务。
如果禁用了 worker（例如 dev profile），db_polling 任务会一直保持 PENDING，
直到管理员手动触发 `POST /ingestion/jobs/process?jobId=...`。

终态失败会进入 DLQ 流/队列。

## 5. 日志采集

- 应用日志文件：`logs/knowledgeops-agent.log`
- Promtail 抓取 `logs/*.log` 并推送到 Loki
- 链路与请求关联字段：`trace_id`、`request_id`、`chat_id`

## 6. 每日评估器契约检查

```bash
python3 scripts/generate_eval_dataset.py
python3 scripts/generate_eval_contract_fixture.py --dataset evaluation/dataset.large.json --output evaluation/predictions.contract.json
python3 scripts/run_regression.py --dataset evaluation/dataset.large.json --predictions evaluation/predictions.contract.json --report-dir reports/evaluation-contract --threshold 0.75
```

该定时任务只验证评估器契约。模型质量证据来自 `eval_live_runner.py`
以及 `run_regression.py --require-live-predictions`。

## 7. 性能验证

```bash
k6 run performance/k6/chat_ingestion_load.js -e BASE_URL=http://localhost:8080
k6 run performance/k6/distributed_chat_ingestion.js -e BASE_URL=http://localhost:8080
python3 performance/k6/generate_report.py --summary reports/performance/distributed-k6-summary.json
```

## 8. 故障排查手册

1. 核验应用健康端点及各依赖是否可用。
2. 检查摄取队列积压和失败任务。
3. 按 `trace_id` 串联关联日志。
4. 在 Prometheus 中查看 p95 延迟和错误突增。
5. 若 SLA 持续劣化，触发降级或回滚。
