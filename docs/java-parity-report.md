# Java 对等实现报告

本报告记录 Java 版本相对于 KnowledgeOps Agent 三语言（Java、TypeScript、Python）共享对等目标的达成情况。

## 范围

Java 版本是生产基线实现。它保留既有的 Spring Boot、Spring Security、Spring AI、MyBatis-Plus、Flyway、Docker 与可观测性技术栈，同时为共享的三语言契约暴露兼容端点与测试。

## 能力矩阵

| 能力 | Java 状态 | 证据 |
|---|---:|---|
| 认证与租户隔离 | 已完成 | API Key、JWT、refresh token、RBAC、`X-Tenant-ID` 过滤器 |
| 对话 | 已完成 | `/ai/chat` |
| SSE 流式输出 | 已完成 | `/ai/chat/stream`、`/ai/react/chat/stream` |
| ReAct Agent | 已完成 | `/ai/react/chat`、trace 载荷 |
| RAG 入库与问答 | 已完成 | `/ai/pdf/upload/{chatId}`、`/ingestion/upload/{chatId}`、`/ai/pdf/chat` |
| 混合检索 | 已完成 | 向量、关键词、图谱、Web 检索服务 |
| 引用与证据 | 已完成 | `CitationItem`、`EvidenceItem`、RAG 响应 |
| 会话历史 | 已完成 | `/ai/sessions`、分支对比与合并 |
| 反馈与评测 | 已完成 | `/ai/feedback`、`/ai/evaluation/datasets`、`/ai/evaluation/runs` |
| 成本治理 | 已完成 | `/cost/summary`、`/cost/budget` |
| 审计日志 | 已完成 | `/audit/logs`、审计过滤器 |
| 限流 | 已完成 | Bucket4j 过滤器 |
| 健康与指标 | 已完成 | `/actuator/health`、`/actuator/prometheus` |
| Docker 本地部署 | 已完成 | `Dockerfile`、`docker-compose.yml` |
| Helm 部署 | 已完成 | `helm/knowledgeops-agent` |
| API 契约测试 | 已完成 | `JavaApiContractTest` |
| E2E 烟测 | 已完成 | `scripts/e2e_chat_flow.py` |
| 性能烟测 | 已完成 | `performance/k6/chat_ingestion_load.js` |
| 安全默认值检查 | 已完成 | `AppStartupValidator`、`SecurityDefaultsTest` |
| README 与运维文档 | 已完成 | README、运维文档、本报告 |

## 共享端点契约

Java 版本暴露三语言路线共享的端点命名：

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

- 现有 Java 端点保持可用。新的共享端点按需以别名形式加入，不移除既有路由。
- JSON 响应保持当前 Java 响应结构，以兼容前端与烟测。兼容 getter 暴露 `thoughtSummary`、`source`、`snippet`、`principal`、`status` 等共享字段名。
- 全局响应包装统一迁移为严格 `ok/msg/data` 一事有意留作后续协同变更，因为它会影响现有前端与 E2E 烟测的载荷预期。

## 本地 Java 门禁

推送 Java main 分支前先运行：

```bash
mvn -q test
mvn -q -DskipTests package
```

可选部署检查：

```bash
helm lint helm/knowledgeops-agent
docker compose config
```
