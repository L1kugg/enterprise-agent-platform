# KnowledgeOps Agent 证据包

本证据包收集了评审该项目作为可运行 AI 平台时最短的公开验证路径。

## 运行时证据

- 本地验证路径：`./scripts/demo.sh`
- 可拉取镜像：`docker pull ghcr.io/however-yir/knowledgeops-agent:latest`
- 容器构建工作流：`.github/workflows/publish-image.yml`
- 主 CI：`.github/workflows/ci.yml`
- 回归工作流：`.github/workflows/nightly-regression.yml`
- 基线发布：`AI Matrix Baseline 2026.05`
- 发布版本：`v1.0.0 - Enterprise-ready KnowledgeOps Agent`

## 产品与架构证据

- Demo 动图：`docs/assets/screenshots/demo.gif`
- RAG Evaluation Studio 截图：`docs/assets/evaluation-report-studio.png`
- RAG 引用截图：`docs/assets/rag-answer-citations.png`
- 架构总览图：`docs/assets/architecture-overview.svg`
- 工作流架构：`docs/architecture-agent-workflow.md`
- 混合检索架构：`docs/architecture-hybrid-retrieval.md`
- 知识图谱架构：`docs/architecture-knowledge-graph.md`
- 记忆系统架构：`docs/architecture-memory-system.md`

## RAG 评测复现

Evaluation Studio 路径使用平台 API 与现有混合 RAG 链路，持久化 `eval_dataset`、`eval_case`、`eval_run`、`eval_result`，并导出一份最新报告文件。

```bash
# 1. 启动本地容器栈
make demo

# 2. 生成最新评测报告
make eval-demo

# 3. 查看报告
open evaluation/reports/latest-evaluation-report.md
```

demo 背后的 API 路径：

1. `POST /ai/evaluation/datasets`
2. `POST /ai/evaluation/datasets/{datasetId}/runs`
3. `GET /ai/evaluation/datasets/{datasetId}/comparison`
4. `GET /ai/evaluation/runs/{runId}/report`

看板路径：

- 打开 `http://localhost:8088`
- 切换到 `Evaluation`
- 对比基线与当前指标：检索命中率、引用覆盖率、回答忠实度、平均延迟、失败率、运行总分。

## 跨仓库集成证据（KnowledgeOps → tianji）

以下证据表明"KnowledgeOps→tianji"的矩阵联动是可运行的代码路径，而不只是 README 里的一个箭头。

### 前置条件

```bash
# 1. 启动 KnowledgeOps Agent 容器栈
cd knowledgeops-agent && ./scripts/demo.sh

# 2. 启动 tianji-ai-agent 容器栈（另开一个终端）
cd tianji-ai-agent && bash scripts/quick-start-mac.sh

# 3. 为 tianji 设置跨仓库环境变量
export TJ_AI_KNOWLEDGEOPS_ENABLED=true
export TJ_AI_KNOWLEDGEOPS_BASE_URL=http://localhost:8080
export TJ_AI_KNOWLEDGEOPS_API_KEY=your-api-key
```

### 验证步骤

1. **Web 检索可用**：在 `APP_WEB_SEARCH_ENABLED=true` 且配置了 SearXNG 实例的前提下，发送一个研究类查询 → 确认 `retrieval.web.latency` 指标显示 `outcome=success`（而不是 `disabled` 或 `no-backend`）。
2. **KnowledgeOpsClient 打通 KnowledgeOps**：在 tianji 侧设置 `TJ_AI_KNOWLEDGEOPS_ENABLED=true` 后，发送 KNOWLEDGE 或 RECOMMEND 提问 → 在 tianji 日志中检查 `KnowledgeOps platform RAG` 或 `KnowledgeOps platform memory` 的增强日志。
3. **降级可用**：设置 `TJ_AI_KNOWLEDGEOPS_ENABLED=false` 后发送同样的提问 → tianji 的 KnowledgeAgent 与 RecommendAgent 无报错地降级到本地 VectorStore Advisor。
4. **两个仓库的 CI 均为绿色**：打开两个仓库最近一次 GitHub Actions 运行记录，确认 main 分支推送为 `✓`。

### 跨仓库 Docker Compose（最小配置）

```yaml
# docker-compose.cross-repo.yml — 用于本地跨仓库验证
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

### 证据清单

| 证据 | 验证方式 |
|---|---|
| Web 检索返回结果 | 检查 `retrieval.web.latency` 指标为 `outcome=success` |
| tianji 打通 KnowledgeOps | 检查 tianji 日志中的 `KnowledgeOps platform RAG` 调试信息 |
| 无 KnowledgeOps 时降级 | 关闭 `TJ_AI_KNOWLEDGEOPS_ENABLED` → agent 使用本地 Advisor |
| 两个仓库 CI 均为绿色 | 打开两个仓库 main 分支最近一次 GitHub Actions 运行 |

## 验证检查清单

- 从干净检出启动 demo 容器栈。
- 上传或种入一份知识文档。
- 执行一次 RAG 问答并确认返回引用与证据。
- 执行一次 Agent 工作流并确认任务/步骤/事件状态可见。
- 查看 `docs/observability.md` 中的 Prometheus/Grafana/链路追踪文档。
- 打开最近一次 GitHub Actions 运行，确认基线 CI 为绿色。
- *（跨仓库）* KnowledgeOps 运行时，验证 tianji 的 KnowledgeAgent 通过 KnowledgeOpsClient 打通它。
- *（跨仓库）* KnowledgeOps 停止时，验证 tianji 的 KnowledgeAgent 降级到本地 Advisor。
