# 分布式负载与可观测性演练

## 目标

验证两条生产关键链路：

1. 分布式负载稳定性（`multi app instances + queue backend + cache`）
2. 告警闭环（`Prometheus + Alertmanager + Loki + Tempo`）

## 拓扑（模拟）

- 应用：2~3 个实例（`docker compose --scale app=3`）
- 队列：Redis Streams 或 RabbitMQ
- 存储：MySQL + pgvector（可选）
- 可观测性：Prometheus + Alertmanager + Loki + Tempo + Promtail

## 操作手册

1. 启动应用栈：
`docker compose up -d`

2. 启动可观测性栈：
`docker compose -f docker-compose.observability.yml up -d`

3. 运行分布式压测：
`k6 run performance/k6/distributed_chat_ingestion.js -e BASE_URL=http://localhost:8080 -e BEARER_TOKEN=xxx`

3.1 生成报告：
`python3 performance/k6/generate_report.py --summary reports/performance/distributed-k6-summary.json`

4. 故障注入演练（可选）：
- 停掉一个应用实例
- 停止一次队列消费者进程

5. 验证指标：
- p95 延迟
- 错误率
- 队列积压
- 重试成功率

6. 验证链路追踪/日志：
- trace 中包含 `request_id/trace_id/session_id/job_id`
- 日志可按 `trace_id` 过滤

7. 验证告警闭环：
- 触发条件：高延迟 / 错误率飙升
- Alertmanager 收到告警
- 负载停止后出现恢复告警

## 交付物

- `reports/performance/distributed-k6-summary.json`
- `reports/performance/k6-report.md`
- Grafana 仪表盘截图
- 告警触发（firing）与恢复（resolved）截图
- 一页复盘记录
