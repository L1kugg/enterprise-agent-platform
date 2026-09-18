# Java 对齐报告

本报告记录 Java 版本相对于 KnowledgeOps Agent 三语言（Java、TypeScript、Python）共同对齐目标的就绪情况。

## 范围

Java 版本是功能基线和面向生产的原型。它保留了既有的 Spring Boot、Spring Security、Spring AI、MyBatis-Plus、Flyway、Docker 及可观测性技术栈，同时为三语言共享契约提供兼容端点和测试。

## 能力矩阵

| 能力 | Java 状态 | 证据 |
|---|---:|---|
| 认证与租户隔离 | 已完成 | API key、JWT、refresh token、RBAC、由身份派生的租户上下文 |
| 聊天 | 已完成 | `/ai/chat` |
| SSE 流式 | 已完成 | `/ai/chat/stream`、`/ai/react/chat/stream` |
| ReAct Agent | 已完成 | `/ai/react/chat`、trace 载荷 |
| RAG 摄取与问答 | 已完成 | `/ai/pdf/upload/{chatId}`、`/ingestion/upload/{chatId}`、`/ai/pdf/chat` |
| 混合检索 | 已完成 | 向量、关键词、图谱、Web 检索服务 |
| 引用与证据 | 已完成 | `CitationItem`、`EvidenceItem`、RAG 响应 |
| 会话历史 | 已完成 | `/ai/sessions`、分支对比与合并 |
| 反馈与评估 | 已完成 | `/ai/feedback`、`/ai/evaluation/datasets`、`/ai/evaluation/runs` |
| 成本治理 | 已完成 | `/cost/summary`、`/cost/budget` |
| 审计日志 | 已完成 | `/audit/logs`、审计过滤器 |
| 限流 | 仅单实例 | Bucket4j 内存过滤器；共享 Redis 后端尚未实现 |
| 健康检查与指标 | 已完成 | `/actuator/health`、`/actuator/prometheus` |
| Docker 本地部署 | 已完成 | `Dockerfile`、`docker-compose.yml` |
| Helm 部署 | 已完成 | `helm/knowledgeops-agent` |
| API 契约测试 | 已完成 | `JavaApiContractTest` |
| E2E 冒烟 | 已完成 | `scripts/e2e_chat_flow.py` |
| 性能冒烟 | 已完成 | `performance/k6/chat_ingestion_load.js` |
| 安全默认值检查 | 已完成 | `AppStartupValidator`、`SecurityDefaultsTest` |
| README 与运维文档 | 已完成 | README、运维文档、本报告 |

## 共享端点契约

Java 版本提供三语言路线共同约定的端点名称：

- `POST /auth/token`
- `POST /auth/refresh`
- `POST /auth/api-keys`
- `GET /actuator/health`
- `GET /actuator/prometheus`
- `POST /ai/chat`
- `POST /ai/chat/stream`
- `POST /ai/react/chat`
- `POST /ai/react/chat/stream`
- `POST /ai/pdf/upload/{chatId}`
- `POST /ingestion/upload/{chatId}`
- `GET /ingestion/jobs`
- `GET /ingestion/jobs/{jobId}`
- `POST /ai/pdf/chat`
- `GET /ai/sessions`
- `GET /ai/sessions/{sessionId}`
- `POST /ai/feedback`
- `GET /ai/evaluation/datasets`
- `POST /ai/evaluation/runs`
- `GET /audit/logs`
- `GET /cost/summary`
- `POST /cost/budget`

## 兼容性说明

- 既有的 Java 端点仍然可用。新的共享端点在需要时以别名形式添加，而不是删除现有路由。
- JSON 响应保持当前 Java 的响应结构，以保证前端与冒烟测试兼容。兼容性 getter 暴露共享字段名，例如 `thoughtSummary`、`source`、`snippet`、`principal` 和 `status`。
- 将全局响应包络统一迁移为严格的 `ok/msg/data` 有意留待后续协同变更，因为这会影响当前前端和 E2E 冒烟测试的载荷预期。

## 本地 Java 门禁

推送 Java main 之前请先执行：

```bash
mvn -q test
mvn -q -DskipTests package
```

可选的部署检查：

```bash
helm lint helm/knowledgeops-agent
docker compose config
```
