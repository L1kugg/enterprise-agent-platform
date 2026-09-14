# 更新日志

本文件记录本项目的所有重要变更。

## [Unreleased]

- 暂无未发布的变更。

## [1.0.0] - 2026-04-28

### 新增
- 基于 Redis Stream 的入库队列，支持 DLQ、失败重试重新入队与多 worker 并发。
- RabbitMQ 入库队列后端，独立声明 queue/DLX/DLQ，并支持并发监听器。
- pgvector 正式迁移与回滚脚本。
- API key 生命周期（签发/轮换/吊销/过期）与 JWT refresh token 流程。
- 权限粒度的安全路由与审计日志保留期调度器。
- RAG 分块、重排序、多文档融合与答案引用。
- 可观测性组件模板（Prometheus、Loki、Tempo、Alertmanager、Promtail）。
- OpenAPI 集成、压测脚本、大规模夜间评测流水线。
- ReAct agent 端点（`/ai/react/chat`、`/ai/react/chat/stream`），带 trace payload 与 SSE 事件。
- Vue3 + TypeScript + Element Plus 前端控制台，支持 Markdown 渲染、暗色模式、响应式布局与 ReAct trace 视图。
- Docker Compose 中的 Nginx 反向代理 Web 服务，支持一条命令启动全栈。
- 开发环境演示用管理员 API key 种子数据（`dev-admin-key-2026`），用于本地走通认证流程。
- 快速 Maven 测试通道，并提供独立的 `integration-test` profile 用于容器化冒烟测试。
- 基于流的 SHA-256 哈希工具与 PDF 安全扫描器测试。
- Flyway 迁移 `V9`，为 `conversation` 与 `ingestion_job` 引入租户隔离。
- PostgreSQL pgvector 租户感知元数据索引（`tenant_id`、`tenant_id + chat_id`）。

### 变更
- PDF 入库从数据库轮询循环切换为队列驱动的 worker 模型。
- API key 轮换改为按稳定的 `keyName`（活跃 key 语义）轮换，不再临时生成名称。
- 向量存储后端默认值调整为偏向 pgvector 生产路径。
- 项目命名与运行时标识统一为企业平台术语（`knowledgeops-agent`）。
- README 与文档升级为聚焦企业部署与架构的文档体系。
- 除 development profile 外，应用安全默认启用。
- 自动入库幂等键改用文件内容哈希，不再使用文件名与文件大小。
- PDF 安全扫描改为仅读取文件头，并在入库前校验 PDF 魔数。
- 前端生产构建将 Vue、Element Plus 与 Markdown/高亮依赖拆分为独立 vendor chunk。
- 聊天历史、聊天记忆、入库任务 API 与 PDF 下载/列表操作均改为租户隔离（`tenant_id`），防止跨租户数据泄露。
- RAG 检索过滤改为租户感知（`tenant_id && chat_id`），入库元数据包含 `tenant_id`。
- ReAct 流式端点改为输出真实的模型 token 流，而非人工切分答案文本。
- 成本预算更新端点在请求体缺少 `tenantId` 时，回退读取请求中的租户 header。
- 入库运行指标的 submitted/finished/duration 序列新增租户标签。
