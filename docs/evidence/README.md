# KnowledgeOps Agent 证据包

本文件汇集了把项目当作可运行 AI 平台来评审时最短的公开证据路径。

## 运行时证据

- 本地验证路径：`./scripts/demo.sh`
- 可拉取镜像：`docker pull ghcr.io/however-yir/knowledgeops-agent:latest`
- 容器工作流：`.github/workflows/publish-image.yml`
- 主 CI：`.github/workflows/ci.yml`
- 回归工作流：`.github/workflows/nightly-regression.yml`
- 基线发布：`AI Matrix Baseline 2026.05`
- 发布版本：`v1.0.0 - KnowledgeOps Agent Platform Prototype`

## 产品与架构证据

- 演示 GIF：`docs/assets/screenshots/demo.gif`
- RAG Evaluation Studio 截图：`docs/assets/evaluation-report-studio.png`
- RAG 引用截图：`docs/assets/rag-answer-citations.png`
- 架构总览：`docs/assets/architecture-overview.svg`
- 工作流架构：`docs/architecture-agent-workflow.md`
- 混合检索架构：`docs/architecture-hybrid-retrieval.md`
- 知识图谱架构：`docs/architecture-knowledge-graph.md`
- 记忆系统架构：`docs/architecture-memory-system.md`

## RAG 评测复现

Evaluation Studio 路径使用平台 API 与现有 Hybrid RAG 链路。它会持久化 `eval_dataset`、`eval_case`、`eval_run`、`eval_result`，然后导出一份最新报告文件。

```bash
# 1. Start the local stack
make demo

# 2. Generate the latest evaluation report
make eval-demo

# 3. Inspect the report
open evaluation/reports/latest-evaluation-report.md
```

演示背后的 API 路径：

1. `POST /ai/evaluation/datasets`
2. `POST /ai/evaluation/datasets/{datasetId}/runs`
3. `GET /ai/evaluation/datasets/{datasetId}/comparison`
4. `GET /ai/evaluation/runs/{runId}/report`

看板路径：

- 打开 `http://localhost:8088`
- 切换到 `Evaluation`
- 对比基线与当前指标：检索命中率、引用覆盖率、回答忠实度、平均延迟、失败率、运行评分。

## 跨仓库集成证据（KnowledgeOps → tianji）

以下证据表明"KnowledgeOps→tianji"矩阵链路是可运行的代码路径，而不只是 README 上的一个箭头。

### 前置条件

```bash
# 1. Start KnowledgeOps Agent stack
cd knowledgeops-agent && ./scripts/demo.sh

# 2. Start tianji-ai-agent stack (in another terminal)
cd tianji-ai-agent && bash scripts/quick-start-mac.sh

# 3. Set cross-repo environment variables for tianji
export TJ_AI_KNOWLEDGEOPS_ENABLED=true
export TJ_AI_KNOWLEDGEOPS_BASE_URL=http://localhost:8080
export TJ_AI_KNOWLEDGEOPS_API_KEY=your-api-key
```

### 验证步骤

1. **Web 检索可用**：在 `APP_WEB_SEARCH_ENABLED=true` 且配置了 SearXNG 实例的前提下，发送一个研究类查询 → 确认 `retrieval.web.latency` 指标显示 `outcome=success`（而不是 `disabled` 或 `no-backend`）。
2. **KnowledgeOpsClient 连通 KnowledgeOps**：在 tianji 中设置 `TJ_AI_KNOWLEDGEOPS_ENABLED=true` 后，发送 KNOWLEDGE 或 RECOMMEND 提示词 → 在 tianji 日志中检查 `KnowledgeOps platform RAG` 或 `KnowledgeOps platform memory` 增强消息。
3. **兜底生效**：设置 `TJ_AI_KNOWLEDGEOPS_ENABLED=false` 后发送相同提示词 → tianji 的 KnowledgeAgent 与 RecommendAgent 无报错地回退到本地 VectorStore Advisor。
4. **两个仓库的 CI 均为绿色**：打开两个仓库最新的 GitHub Actions 运行记录，确认 main 分支推送为 `✓`。

### 跨仓库 Docker Compose（最小化）

```yaml
# docker-compose.cross-repo.yml — for local cross-repo verification
version: "3.8"
services:
  searxng:
    image: searxng/searxng:latest
    ports: ["8888:8080"]
    environment:
      SEARXNG_BASE_URL: http://localhost:8888/

  knowledgeops:
    image: ghcr.io/however-yir/knowledgeops-agent:latest
    ports: ["8080:8080"]
    environment:
      SPRING_PROFILES_ACTIVE: dev
      APP_WEB_SEARCH_ENABLED: "true"
      APP_WEB_SEARCH_BACKEND: searxng
      APP_WEB_SEARCH_SEARXNG_URL: http://searxng:8080
    depends_on: [searxng]

  tianji-aigc:
    image: ghcr.io/however-yir/tianji-ai-agent:demo
    ports: ["8094:8094"]
    environment:
      TJ_AI_KNOWLEDGEOPS_ENABLED: "true"
      TJ_AI_KNOWLEDGEOPS_BASE_URL: http://knowledgeops:8080
    depends_on: [knowledgeops]
```

### 证据产物

| 证据 | 如何验证 |
|---|---|
| Web 检索返回结果 | 检查 `retrieval.web.latency` 指标为 `outcome=success` |
| tianji 连通 KnowledgeOps | 检查 tianji 日志中的 `KnowledgeOps platform RAG` 调试消息 |
| 无 KnowledgeOps 时的兜底 | 关闭 `TJ_AI_KNOWLEDGEOPS_ENABLED` → Agent 使用本地 Advisor |
| 两个仓库 CI 均为绿色 | 打开两个仓库 `main` 分支最新的 GitHub Actions 运行记录 |

## 验证清单

- 从干净的检出目录启动演示栈。
- 上传或植入一份知识文档。
- 运行一次 RAG 问答，确认返回引用/证据。
- 运行一次 Agent 工作流，确认任务/步骤/事件状态可见。
- 查看 `docs/observability.md` 中的 Prometheus/Grafana/链路追踪文档。
- 打开最新的 GitHub Actions 运行记录，确认基线 CI 为绿色。
- *（跨仓库）* 在 KnowledgeOps 运行时，验证 tianji 的 KnowledgeAgent 通过 KnowledgeOpsClient 连通它。
- *（跨仓库）* 在 KnowledgeOps 停止时，验证 tianji 的 KnowledgeAgent 回退到本地 Advisor。
