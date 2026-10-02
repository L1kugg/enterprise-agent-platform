# 可观测性

KnowledgeOps Agent 通过 Prometheus + Grafana + Loki + Tempo 实现全链路可观测。

## 快速启动

```bash
docker compose -f docker-compose.observability.yml up -d
```

- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090

## 核心指标

### Agent 工作流

| 指标 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `agent.workflow.step.latency` | Timer | agent, status | 每步执行延迟 |
| `agent.workflow.step.count` | Counter | agent, status | 步骤执行计数 |
| `agent.workflow.task.latency` | Timer | type, status | 任务整体延迟 |
| `agent.workflow.task.count` | Counter | type, status | 任务执行计数 |
| `agent.workflow.task.reclaimed` | Counter | - | 运行期兜底回收的非终态任务数（Reclaimer，5 分钟周期） |
| `agent.workflow.task.swept` | Counter | - | 启动清扫收尾的上一进程遗留非终态任务数（Sweeper，启动即扫） |
| `research.pool.active` | Gauge | - | 深度研究后台池活跃工作线程数 |
| `research.pool.queued` | Gauge | - | 深度研究后台池排队任务数 |
| `research.queue.wait` | Timer | - | 深度研究任务从受理到开始执行的排队时长 |
| `research.task.rejected` | Counter | - | 深度研究队列满被拒的受理数（对外 429） |

### RAG 管线

| 指标 | 说明 |
|---|---|
| `rag.pipeline.latency` | RAG 管线延迟（tag: outcome=success/empty/error） |
| `rag.pipeline.requests` | RAG 请求计数 |
| `rag.retrieval.latency` | 向量检索延迟 |
| `rag.rerank.latency` | 重排序延迟 |
| `rag.hybrid.pipeline.latency` | 混合检索管线延迟 |
| `retrieval.vector.latency` | VectorRetriever 延迟 |
| `retrieval.keyword.latency` | KeywordRetriever 延迟 |
| `retrieval.graph.latency` | GraphRetriever 延迟 |
| `retrieval.web.latency` | WebRetriever 延迟（web 路禁用时不产生） |
| `retrieval.hybrid.latency` | 融合去重延迟 |
| `retrieval.queue.wait` | 每路任务从提交到开始执行的排队时长 Timer（tag: source；超时预算从执行起算，排队不再烧预算） |
| `retrieval.source.requests` | 每路检索结局计数（tags: source, outcome=success/error/timeout/**saturated**；saturated = 池/队列打满提交被拒） |

### 证据评分

| 指标 | 说明 |
|---|---|
| `evidence.judge.latency` | 证据评分延迟 |

### ReAct 流式

| 指标 | 说明 |
|---|---|
| `react.stream.total.latency` | 端到端流式延迟（tag: outcome） |
| `react.stream.first_token.latency` | 首 token 延迟 |
| `react.stream.requests` | 流式请求计数 |

## Grafana 仪表盘

预置面板：

| 面板 | 说明 |
|---|---|
| Request Rate | HTTP 请求速率 |
| P95 Latency | HTTP P95 延迟 |
| Error Rate | 错误率 |
| RAG Pipeline | RAG 管线延迟分布 |
| Ingestion | 入库任务状态 |
| JVM Heap | JVM 堆内存 |
| HikariCP Pool | 数据库连接池 |
| Agent Workflow | Agent 步骤延迟和计数（v1 新增） |

## 告警规则

| 规则 | 条件 | 级别 |
|---|---|---|
| HighHttpP95Latency | HTTP P95 > 2s | warning |
| IngestionFailureRateHigh | 入库失败率 > 5% | warning |
| DiskSpaceLow | 磁盘 < 15% | critical |
| HikariPoolExhausted | 连接池 pending > 10 | critical |
| JvmMemoryHigh | 堆使用 > 85% | warning |

## 链路追踪

- OTLP 导出到 Tempo
- 采样率通过 `OTEL_SAMPLING_PROBABILITY` 配置
- 日志中包含 `trace_id`，支持按 trace 串联请求日志

## 结构化日志

JSON 格式，包含以下字段：

- `request_id` - 请求 ID
- `trace_id` - 追踪 ID
- `tenant_id` - 租户 ID
- `chat_id` - 会话 ID
- `level` - 日志级别
- `message` - 日志内容

## 健康检查

- `/actuator/health` - 含 Kubernetes liveness/readiness 探针
- `/actuator/prometheus` - Prometheus 指标端点
