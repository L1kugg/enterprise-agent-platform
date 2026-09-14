# AI 工程项目矩阵

本仓库是五项目 AI 工程作品集的组成部分。矩阵用于说明每个项目的定位，避免在每个 README 首页重复完整表格。

| 仓库 | 定位 | 核心场景 | 工程证明 |
|---|---|---|---|
| [knowledgeops-agent](https://github.com/however-yir/knowledgeops-agent) | 企业级 Spring AI RAG 平台 | 受治理的企业知识问答 | Spring AI、RAG、JWT/RBAC、异步入库、可观测性、回归评测 |
| [tianji-ai-agent](https://github.com/however-yir/tianji-ai-agent) | 业务 Agent 工程案例 | 课程咨询、推荐与预下单流程 | Java、Spring AI、多 Agent 路由、Tool Calling、MCP、SSE、多模态入口 |
| [nebula-kb](https://github.com/however-yir/nebula-kb) | 本地 AI 知识平台 | 知识生命周期 + RAG 引擎（DeepDoc）+ AI 对话（Open WebUI） | Django、PostgreSQL、Redis、RAGFlow、Open WebUI、生命周期工作流 |
| [forgepilot-studio](https://github.com/however-yir/forgepilot-studio) | AI 工程执行工作台 | 面向团队的可审计 AI 编码任务执行 | Python、FastAPI、React、运行时沙箱、MCP 治理、审计回放 |
| [however-microservices-lab](https://github.com/however-yir/however-microservices-lab) | 云原生微服务与 AI 实验室 | 集成 AI 助手的多语言微服务 | Go、Python、Java、Node.js、C#、Kubernetes、gRPC、Ollama/Gemini |

## 本仓库在矩阵中的位置

`knowledgeops-agent` 是其中的企业后端切片。它证明 RAG 可以作为一个受治理的平台来实现：具备租户边界、异步入库、可审计、可观测以及可重复的质量校验。

## 跨仓库验证

KnowledgeOps 与 tianji 之间的矩阵关联通过以下方式验证：

1. **代码路径**：tianji 中的 `KnowledgeOpsClient` 调用 KnowledgeOps Agent 的 REST API（`/ai/rag/search`、`/ai/memory/query`、`/ai/graph/search`）
2. **降级策略**：平台不可用时，tianji agent 降级到本地 VectorStore + Advisor
3. **CI 证据**：两个仓库的 `main` 分支 CI 均为绿色
4. **Docker compose**：跨仓库 Docker Compose（2 个服务 + 3 个环境变量）见 `docs/evidence/README.md`
5. **交互式架构页**：[however-yir.github.io/matrix](https://however-yir.github.io/matrix) — 点击任意节点可跳转到对应的 Evidence Pack
