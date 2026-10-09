# Enterprise Agent Platform 文档

Enterprise Agent Platform 是一个企业级 Spring AI RAG 平台，提供租户隔离检索、异步文档摄取、受治理的 Agent 工作流、可审计的安全体系、生产级可观测性以及回归评估。

![Enterprise Agent Platform 架构](assets/architecture-overview.svg)

## 按需选择入口

| 目标 | 从这里开始 | 你能获得 |
|---|---|---|
| 本地运行 | [快速开始](getting-started.md) | Docker Compose 启动、健康检查、认证 token 流程与清理 |
| 走一遍演示 | [可复现演示脚本](demo-script.md) | 演示数据、PDF 上传、异步摄取、RAG 问答、租户与权限校验 |
| 调用 API | [API 使用示例](api-recipes.md) | 可直接复制的 curl 示例，覆盖聊天、RAG、摄取、ReAct 与可观测性 |
| 理解系统 | [企业级架构说明](architecture-enterprise.md)、[Agent Harness 架构](architecture-agent-harness.md) | 服务边界、Agent 工具执行、数据流、安全与可观测性架构 |
| 部署上线 | [企业级部署指南](deployment-enterprise.md) | 生产拓扑、发布检查、环境变量与上线注意事项 |
| 日常运维 | [运维手册](operations.md) | 指标、日志、链路追踪、告警、故障演练与回归检查 |
| 跟踪后续工作 | [路线图](roadmap.md) | v1.1.0 重点关注方向与待办事项 |

## 推荐的最初 15 分钟

1. 从[快速开始](getting-started.md)入手，运行 `./scripts/demo.sh`。
2. 打开[可复现演示脚本](demo-script.md)，上传 `demo-data/heat-safety-policy.pdf`。
3. 参考 [API 使用示例](api-recipes.md)，用演示 API Key 换取 JWT。
4. 依次尝试 `/ai/chat`、`/ingestion/upload/{chatId}`，然后是 `/ai/pdf/chat`。
5. 在修改数据流或安全边界之前，先阅读[企业级架构说明](architecture-enterprise.md)。
6. 在修改队列、向量存储或可观测性配置之前，先查阅[运维手册](operations.md)。

## 可视化验证

| 入口 | 预览 |
|---|---|
| 控制台工作区 | ![控制台总览](assets/console-overview.png) |
| 带引用的 RAG 回答 | ![带引用的 RAG 回答](assets/rag-answer-citations.png) |

## 文档地图

| 分类 | 文档 |
|---|---|
| 产品概览 | [项目 README](https://github.com/L1kugg/enterprise-agent-platform#readme)、[路线图](roadmap.md) |
| 本地评估 | [快速开始](getting-started.md)、[可复现演示脚本](demo-script.md)、[API 使用示例](api-recipes.md) |
| 架构与部署 | [企业级架构说明](architecture-enterprise.md)、[Agent 工作流](architecture-agent-workflow.md)、[Agent Harness 架构](architecture-agent-harness.md)、[企业级部署指南](deployment-enterprise.md) |
| 运维 | [运维手册](operations.md)、[分布式与可观测性演练](drills/distributed-and-observability-drill.md)、[Runbook 模板](drills/runbook_template.md) |
| 技术讲解要点 | [证据清单](talking-points/evidence-checklist.md) |

## 平台能力

| 领域 | 覆盖范围 |
|---|---|
| AI 工作流 | 聊天、PDF RAG、ReAct 追踪、受治理的 Agent 执行框架、MCP 适配器运行时、可信工作区运行时、工具调用、会话历史 |
| 摄取 | Redis Stream 或 RabbitMQ 队列、重试、DLQ、幂等、状态跟踪 |
| 安全 | API Key、JWT、refresh token、RBAC、租户隔离、限流、审计日志 |
| 运维 | Docker Compose、Flyway、Prometheus、Loki、Tempo、Alertmanager、结构化日志 |
| 质量 | CI、单元测试、集成测试、回归评估、k6 压测 |

## 运行时链接

本地技术栈启动后可访问以下入口：

| 入口 | URL |
|---|---|
| 前端控制台 | `http://localhost:8088` |
| 后端 API | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| Prometheus 指标 | `http://localhost:8080/actuator/prometheus` |
| RabbitMQ 控制台 | `http://localhost:15672` |

## 发布与社区

- 最新版本：[v1.0.0](https://github.com/L1kugg/enterprise-agent-platform/releases/tag/v1.0.0)
- 路线图里程碑：[v1.1.0](https://github.com/L1kugg/enterprise-agent-platform/milestone/1)
- 讨论区：[GitHub Discussions](https://github.com/L1kugg/enterprise-agent-platform/discussions)
- 源码仓库：[L1kugg/enterprise-agent-platform](https://github.com/L1kugg/enterprise-agent-platform)
