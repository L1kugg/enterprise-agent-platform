# KnowledgeOps Agent 项目技术文档

> 基于 Spring AI 构建的多 Agent 企业知识平台：覆盖 Agent 工作流引擎、混合检索（向量+关键词+图谱+Web）、知识图谱、长短期记忆、深度研究、企业 RAG、租户隔离、异步入库、权限审计、全链路可观测。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈](#2-技术栈)
- [3. 系统架构](#3-系统架构)
- [4. Agent 编排体系](#4-agent-编排体系)
- [5. RAG 检索增强管线](#5-rag-检索增强管线)
- [6. 四层记忆系统](#6-四层记忆系统)
- [7. 知识图谱](#7-知识图谱)
- [8. Agent Harness 工具执行层](#8-agent-harness-工具执行层)
- [9. 安全体系](#9-安全体系)
- [10. 容错与兜底](#10-容错与兜底)
- [11. 可观测性](#11-可观测性)
- [12. 评测体系](#12-评测体系)
- [13. 数据库设计](#13-数据库设计)
- [14. API 概览](#14-api-概览)
- [15. 部署与运维](#15-部署与运维)
- [16. 已知局限与改进方向](#16-已知局限与改进方向)

---

## 1. 项目概述

### 1.1 项目定位

KnowledgeOps Agent 是一个企业级 Spring AI RAG 平台，不停留在单接口聊天示例，而是把知识入库、检索问答、租户与权限边界、审计可追溯、可观测运维、质量回归放在同一条可验证链路里。

### 1.2 解决的核心问题

| 问题 | 解决方案 |
|------|---------|
| 对话能力如何稳定落在业务流程中 | Agent 工作流引擎 + ReAct 循环 + 多 Agent 分工 |
| PDF/文档知识如何接入检索增强链路并保证可追溯 | 异步入库 + 混合检索 + 证据评分 + 引用溯源 |
| 工具调用如何具备权限边界、审计记录和失败可恢复 | Agent Harness + 策略守卫 + 事件溯源 + 分层容错 |
| 如何实现线上可运维 | 全链路指标 + 日志 + 链路追踪 + 告警 + 回归评测闭环 |

### 1.3 Agent 形态

项目同时支持单 Agent 与多 Agent 两种编排模式：

| 模式 | 实现 | 特点 |
|------|------|------|
| **单 Agent 自主决策** | `ReactAgentService` | ReAct 循环，模型自主决定调什么工具，最多 4 步 |
| **多 Agent 分工协作** | `DeepResearchService` | 规划 Agent 拆解问题 → 检索 Agent 逐题找证据 → 写作 Agent 综合报告 |
| **通用编排引擎** | `AgentWorkflowEngine` | 状态机驱动，不绑定 Agent 类型，任务/步骤/事件全量持久化 |

---

## 2. 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| 语言/框架 | Java / Spring Boot | 17 / 3.4.5 |
| AI 框架 | Spring AI | 1.1.7 |
| 安全 | Spring Security + JJWT | 6.x + 0.13.0 |
| 弹性 | Resilience4j | 2.4.0 |
| 限流 | Bucket4j + Redis | 8.10.1 |
| ORM | MyBatis-Plus | 3.5.16 |
| 关系数据库 | MySQL + HikariCP | 8.x |
| 缓存/队列 | Redis / RabbitMQ | 7.x / 3.x |
| 向量存储 | pgvector / SimpleVectorStore | — |
| 前端 | Vue 3 + TypeScript + Element Plus | — |
| 可观测 | OpenTelemetry + Micrometer + Prometheus + Grafana + Loki + Tempo | — |
| 静态分析 | Checkstyle / PMD / SpotBugs / OWASP / CycloneDX | — |
| 测试 | JUnit 5 + Testcontainers + JaCoCo | — |

### 2.1 模型配置

```yaml
spring.ai.openai:
  base-url: https://dashscope.aliyuncs.com/compatible-mode
  chat.options.model: qwen-plus
  embedding.options:
    model: text-embedding-v4
    dimensions: 1024
```

### 2.2 模型路由三档

| Profile | 模型 | 成本档 | 降级链 |
|---------|------|--------|--------|
| economy | qwen-turbo | low | — |
| balanced | qwen-plus | medium | → economy |
| quality | qwen-max | high | → balanced → economy |

---

## 3. 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端（Web / API）                        │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                     REST API 层（Controllers）                    │
│  Chat / React / Workflow / DeepResearch / Pdf / Ingestion       │
│  Auth / Audit / Harness / Evaluation / CostGovernance           │
└──┬──────────┬──────────┬──────────┬──────────┬─────────────────┘
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────────┐
│ Agent │ │  RAG  │ │Memory │ │Graph  │ │  Agent    │
│ 编排  │ │ 管线  │ │ 系统  │ │ 服务  │ │  Harness  │
└───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └─────┬─────┘
    │         │         │         │           │
    ▼         ▼         ▼         ▼           ▼
┌─────────────────────────────────────────────────────────────────┐
│                      数据与基础设施层                             │
│  MySQL（会话/任务/记忆/图谱/审计）                                │
│  pgvector（向量索引） / Redis（缓存/队列） / RabbitMQ（消息）      │
│  本地文件存储（PDF 原始文件）                                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Agent 编排体系

### 4.1 工作流状态机

`AgentWorkflowEngine` 管理任务从创建到终态的完整生命周期：

```
CREATED → PLANNING → SEARCHING → RETRIEVING → JUDGING → REFLECTING → WRITING → DONE
                ↘ FAILED ←─────────────────────────────────────────────↙
                ↗ NEED_MORE_EVIDENCE → SEARCHING（循环补充证据）
```

核心职责：

| 职责 | 实现 |
|------|------|
| 任务管理 | `agent_task` 表记录生命周期 |
| 步骤追踪 | `agent_step` 持久化输入/输出/耗时/Token 用量 |
| 事件溯源 | `agent_event` 异步记录所有状态变更，不阻塞主流程 |
| 指标采集 | Micrometer Timer/Counter 记录每步延迟和任务整体延迟 |

### 4.2 单 Agent：ReAct 循环

`ReactAgentService` — 经典 Thought → Action → Observation 循环：

```
用户提问
  → 模型思考并选择动作（query_school / query_course / add_course_reservation / rag_search / query_database / finish）
  → Harness 执行动作，返回 observation
  → observation 拼入滚动上下文
  → 模型再次思考
  → ... 最多 MAX_STEPS = 4 轮
  → 强制生成最终答案（带引用脚注）
```

关键设计：
- **MAX_STEPS = 4** 硬上限防止无限循环
- 规划失败时走 `fallbackDecision()` 按关键词规则兜底
- SSE 流式接口分 `trace` / `token` / `done` / `error` 四种事件

### 4.3 多 Agent：DeepResearch 流水线

`DeepResearchService` — 三个职责不同的 Agent 串行协作：

| Agent | 职责 | 输入 → 输出 |
|-------|------|------------|
| `ResearchPlannerAgent` | 主题拆解 | 研究主题 → 子问题列表 + 检索策略 |
| 混合检索（RagResearchAgent） | 逐题检索 | 子问题 → 证据文档列表 |
| `ReportWriterAgent` | 综合报告 | 全部证据 → 结构化研究报告 |

每个 Agent 的执行步骤通过 `AgentWorkflowEngine.startStep()/completeStep()` 持久化，含耗时与 Token 记录。

---

## 5. RAG 检索增强管线

### 5.1 管线全景

项目有两条 RAG 管线：

| | `RagAnswerService`（基础） | `HybridRagAnswerService`（混合） |
|---|---|---|
| 入口 | `/ai/pdf/chat` | `/ai/rag/search`、Agent `rag_search` |
| 检索 | 单路向量 | 四路混合（向量+关键词+图谱+Web） |
| 评分 | 本地 Token 重叠重排 | 三轮逐层精选 + 三维证据评分 |
| 引用 | 简单文本标注 | 结构化编号引用 + 可信度百分比 |

### 5.2 数据处理（异步入库）

```
PDF 上传
  → FileSafetyScanner 安全扫描（类型/大小检查）
  → 幂等键计算（tenantId + chatId + 文件内容 SHA256）
  → 持久化文件到磁盘
  → 创建 IngestionJob（status=PENDING）
  → 发布到队列（Redis Stream / RabbitMQ / DB 轮询三选一）
  → Worker 消费（多线程，消费者组负载分配）
      → PagePdfDocumentReader 按页解析
      → TokenTextSplitter 切片（chunkSize=800, minChunkSize=120）
      → 每个 chunk 打租户隔离元数据（tenant_id / chat_id / job_id / file_name / chunk_index）
      → EmbeddingModel 向量化（1024 维）
      → 写入 pgvector
```

失败处理：指数退避重试（10s/20s/30s，最多 3 次）→ 重试耗尽写入死信队列（DLQ）→ 任务标记 FAILED。

### 5.3 向量索引

| 后端 | 场景 | 配置 |
|------|------|------|
| pgvector | 生产默认 | schema=public, dimensions=1024, table=ai_knowledge_chunks, HNSW 索引 |
| SimpleVectorStore | 本地开发 / pgvector 不可用降级 | 内存存储，可持久化 JSON 快照 |

初始化失败时：`require-pgvector=true` 直接抛异常（生产模式）；否则降级到 SimpleVectorStore。

### 5.4 四路并行混合召回

`HybridRetrievalService` — 每路独立 `CompletableFuture`，3 秒超时，单路失败静默降级：

| 检索器 | 权重 | 检索方式 | 适用场景 |
|--------|------|---------|---------|
| VectorRetriever | 0.40 | pgvector 语义检索，topK=12，相似度≥0.45，租户+会话过滤 | 语义近似匹配 |
| KeywordRetriever | 0.25 | 标题×0.6 + 内容×0.4 Token 重叠评分，召回 2 倍候选再精筛 | 精确术语匹配 |
| GraphRetriever | 0.20 | 实体搜索 + 一跳邻居 + SPO 三元组 | 结构化关联 |
| WebRetriever | 0.15 | SearXNG / Bing API（默认关闭） | 外部最新信息 |

**融合策略**：加权评分 → 内容前 200 字符指纹去重（保留最高分）→ 按 finalScore 降序 → 取 topK。

### 5.5 三轮逐层精选重排

| 轮次 | 位置 | 作用 |
|------|------|------|
| 第一轮：来源加权 | `HybridRetrievalService` | 每路结果乘来源权重，合并去重排序 |
| 第二轮：术语匹配 | `EvidenceJudgeService.scoreRelevance()` | 检索分为基础 + 查询关键词命中加成（每词+0.05，上限+0.3） |
| 第三轮：三维评分 | `EvidenceJudgeService.judge()` | 相关性×0.50 + 权威性×0.30 + 时效性×0.20 |

权威性层级：图谱(0.90) > 向量(0.75) > 关键词(0.65) > 外部搜索(0.50)

时效性衰减：<30天=1.0, <90天=0.9, <180天=0.8, <365天=0.7, 更久=0.5

### 5.6 引用溯源生成

`CitationService` 为每条证据生成编号引用：

```json
{
  "index": 1,
  "sourceType": "vector",
  "title": "enterprise-ai-report.pdf",
  "chunkId": "chunk-3",
  "confidence": 0.86,
  "excerpt": "根据 Gartner 2025 年报告..."
}
```

生成约束（System Prompt）：
- 仅依据上下文作答
- 输出结尾附引用编号 [1][2]
- 上下文不足时明确说明
- temperature=0.2 降低发散

---

## 6. 四层记忆系统

### 6.1 存储设计

四层共用 MySQL `memory_item` 表，靠 `type` 字段区分，不分表：

| 层 | type | 存什么 | TTL | 置信度 |
|----|------|--------|-----|--------|
| 短期 | short | 最近几轮对话要点 | 24 小时 | 0.9 |
| 长期 | long | 用户画像/偏好/目标 | 永不过期 | 0.85 |
| 任务 | task | Agent 任务中间结论 | 30 天 | 0.9 |
| 事实 | fact | RAG 抽取的可引用事实 | 永不过期 | 按抽取结果 |

另有 `memory_event` 表记录全部操作（CREATE/UPDATE/DELETE/EXPIRE/HIT/USE）做事件溯源。

### 6.2 索引设计

```sql
INDEX idx_memory_item_tenant_user_type (tenant_id, user_id, type)  -- 短期/长期召回
INDEX idx_memory_item_tenant_type      (tenant_id, type)           -- 事实召回
INDEX idx_memory_item_source_task      (source_task_id)            -- 任务召回
INDEX idx_memory_item_expires          (expires_at)                -- 定时清理
```

### 6.3 召回方式

| 层 | SQL 逻辑 | 上限 | 隔离粒度 |
|----|---------|------|---------|
| 短期 | `WHERE tenant_id=? AND user_id=? AND type='short' ORDER BY created_at DESC` | 5 条 | 租户+用户 |
| 长期 | 同上，`type='long'` | 10 条 | 租户+用户 |
| 任务 | `WHERE source_task_id=?` | 不限 | 任务级 |
| 事实 | `WHERE tenant_id=? AND type='fact' AND confidence>=0.7` | 5 条 | 租户级（共享） |

`buildContext()` 将短期+长期+事实拼成纯文本注入系统提示词；任务记忆不进通用上下文，Agent 执行期间单独查询。

### 6.4 生命周期管理

- 每日凌晨 3 点定时清理：`DELETE FROM memory_item WHERE expires_at IS NOT NULL AND expires_at < NOW()`
- 事实记忆靠置信度门槛过滤（≥0.7 才进入上下文）

---

## 7. 知识图谱

### 7.1 设计决策

选择 MySQL 邻接表而非 Neo4j：
- 零新增基础设施（复用已有 MySQL）
- 当前场景一跳/两跳查询足够
- 数据模型可直接迁移到 Neo4j / ArangoDB

### 7.2 表结构

| 表 | 内容 |
|----|------|
| `kg_entity` | 实体（name / type / aliases JSON / description / source_id） |
| `kg_relation` | 关系（source_entity_id / target_entity_id / relation_type / weight / evidence_id） |
| `kg_fact` | 事实三元组（subject / predicate / object / confidence / valid_from / valid_to） |

### 7.3 查询能力

- 实体搜索：按名称 / JSON 别名模糊匹配
- 一跳邻居：`JOIN kg_relation` 获取关联实体与关系类型
- 事实检索：按 subject / object 模糊搜索，按置信度排序

---

## 8. Agent Harness 工具执行层

### 8.1 执行链路

```
模型决策 → AgentAction → ActionPolicyGuard → AgentRuntime → AgentObservation → 事件审计 → 回馈 ReAct
```

### 8.2 Policy Guard 前置校验

任何工具执行前过 6 道检查，任一不过直接返回 error observation：

| 检查项 | 拒绝原因码 |
|--------|-----------|
| action 是否注册 | `unsupported_action` |
| 全局禁用列表 | `disabled_action` |
| 租户级白名单 | `tenant_action_denied` |
| 受信运行时要求 | `trusted_runtime_required` |
| 必填字段缺失 | `invalid_action_input` |
| 未知字段注入 | `invalid_action_input` |

### 8.3 三种 Runtime

| Runtime | 执行什么 | 信任级别 |
|---------|---------|---------|
| BuiltinToolRuntime | query_school / query_course / add_course_reservation / rag_search / query_database | 默认可用 |
| McpToolRuntime | 配置化的 MCP 外部工具调用 | 需要 trusted |
| WorkspaceRuntime | 文件读写 / 文本搜索 / 补丁应用 / Shell 命令 | 需要 trusted |

### 8.4 Workspace 安全边界

- 所有路径限制在配置的 workspace root 内
- Shell 走 ProcessBuilder 白名单命令（pwd/ls/rg/git/mvn），超时强制杀进程
- 高危操作需先走 `/ai/harness/actions/preview` 生成一次性确认 token，再走 execute

---

## 9. 安全体系

### 9.1 鉴权链路

```
API Key（X-API-Key）
  → POST /auth/token 换取 JWT
  → JWT 请求级鉴权
  → Refresh Token 续签（14 天）
```

### 9.2 多层防护

| 防护 | 实现 |
|------|------|
| 租户隔离 | X-Tenant-Id 请求头，所有数据按 tenant_id 过滤 |
| RBAC | `PERM_*` / `ROLE_*` 细粒度权限矩阵，注解+路由级校验 |
| 分布式限流 | Bucket4j + Redis，tenant + principal 复合维度，默认 60 req/min |
| 安全响应头 | X-Content-Type-Options / X-Frame-Options / Permissions-Policy |
| CORS 白名单 | 可配置 `APP_CORS_ALLOWED_ORIGINS` |
| 审计日志 | 全请求记录，保留 90 天，定期清理 |
| 敏感信息脱敏 | API Key / Email / 查询参数级脱敏 |
| 上传安全 | 文件类型/大小检查，文件名清洗 |

---

## 10. 容错与兜底

### 10.1 分层容错架构

```
请求入口
  └─ ReAct 循环（max 4 步硬上限）
       └─ AgentHarnessService（统一 try-catch）
            ├─ PolicyGuard（执行前拦截）
            └─ Runtime 执行
                 ├─ 异常 → error observation（回馈模型）
                 └─ 超时 → 各 runtime 独立处理
```

### 10.2 Resilience4j 防护（已织入 LLM 调用链）

**当前状态**：`llm.ModelCallGuard` 统一封装 CircuitBreaker / Retry / TimeLimiter 三个 Bean，以编程式装饰织入全部 LLM 调用点（按场景独立熔断器）：

| 调用点 | 场景标识（熔断器实例） |
|------|------|
| `ReactAgentService` 规划（同步+流式） | `llm.react` |
| `WorkflowReactAgentService`（同步+流式） | `llm.workflow` |
| `RagAnswerService` 生成 | `llm.rag` |
| `HybridRagAnswerService` 生成 | `llm.rag-hybrid` |
| `ResearchPlannerAgent` 规划 | `llm.research-plan` |
| `ReportWriterAgent` 报告生成 | `llm.research-report` |

| 机制 | 生效方式 | 参数与语义 |
|------|------|------|
| CircuitBreaker | 同步调用 + 流式订阅（`acquirePermission`）/ 终态回写 | 失败率≥50% 或慢调用≥50%（>10s）→ 开路 30s → 半开 5 探测；滑动窗口 20 次、最少 10 次才统计；打开后调用不发出、抛 `CallNotPermittedException` 快速失败 |
| Retry | 仅同步调用（流式不重试，避免重复吐字） | 最多 3 次，间隔 2s，仅对瞬时异常重试（`RestClientException` / `WebClientException` / `TimeoutException` / `TransientAiException`）；熔断器异常不重试 |
| TimeLimiter | 流式以 Reactor `timeout` 实现基线兜底 | 30 秒无任何新帧 → 按失败回写熔断器并中断流 |

装饰顺序：同步 `Retry(CircuitBreaker(调用))`——熔断器记录每次尝试，重试由瞬时异常白名单驱动。观测：resilience4j starter 自动发布的 `resilience4j_circuitbreaker_*` tagged 指标（按 `llm.<场景>` 实例区分）+ 自定义计数器 `llm.call.outcome{scenario, outcome=success/error/not_permitted}`。RAG 两条链的生成步骤捕获熔断异常返回固定兜底文案（`generation_fallback`），ReAct / Workflow / DeepResearch 由既有场景兜底承接（见 10.3）。模型路由 fallback 链（10.3 末行）是路由层的独立降级，与本节熔断互补。

### 10.3 各场景兜底行为

| 场景 | 兜底 |
|------|------|
| 四路检索全空 | 返回固定文案"没有检索到可用内容"，不调 LLM |
| 单路检索超时/失败 | 静默返回空列表，其他路补位 |
| MCP HTTP 非 2xx / 超时 | 转 status=error observation，不抛异常 |
| Shell 命令超时 | destroyForcibly 强杀进程，返回超时错误 |
| 模型规划失败 | 按关键词规则走确定性兜底答案 |
| 最终生成失败 | 返回固定文案"当前未能生成最终答案" |
| SSE 流式中途异常 | 发送 event: error 事件，连接不静默断开 |
| 入库失败 | 指数退避重试 3 次 → 死信队列 |
| 模型不可用 | fallback 链逐级降级（quality→balanced→economy） |

---

## 11. 可观测性

### 11.1 技术栈

Prometheus（指标） + Loki（日志） + Tempo（链路追踪） + Alertmanager（告警） + Grafana（可视化）

### 11.2 核心指标

```
# Agent 工作流
agent.workflow.step.latency{agent, status}
agent.workflow.task.latency{type, status}

# RAG 管线
rag.pipeline.latency{outcome=success|empty|error}
rag.hybrid.pipeline.latency{outcome}
rag.retrieval.latency / rag.rerank.latency

# 检索分路
retrieval.vector.latency{outcome}
retrieval.keyword.latency{outcome}
retrieval.graph.latency{outcome}
retrieval.web.latency{outcome=disabled|empty|success}
retrieval.hybrid.latency{outcome}

# 流式
react.stream.first_token.latency{outcome}
react.stream.total.latency{outcome}
```

### 11.3 告警规则

| 规则 | 阈值 |
|------|------|
| HighHttpP95Latency | HTTP P95 > 2s |
| IngestionFailureRateHigh | 入库失败率 > 5% |
| HikariPoolExhausted | 连接池 pending > 10 |
| JvmMemoryHigh | 堆使用 > 85% |
| DiskSpaceLow | 磁盘 < 15% |

### 11.4 日志与追踪

- JSON 结构化日志，含 request_id / trace_id / tenant_id / chat_id
- OTLP 导出到 Tempo，采样率可配置
- 按 trace_id 串联请求日志与调用链

---

## 12. 评测体系

### 12.1 两套评测

| | CI 回归门禁 | Evaluation Studio |
|---|---|---|
| 运行方式 | Python 脚本离线对比 | 真实调 HTTP API |
| 触发 | 每次 PR + 每晚定时 | `make eval-demo` |
| 作用 | 卡 CI 红绿灯 | 质量看板 |

### 12.2 数据集设计

四个维度，每条 case 带正向约束（必须命中）与反向约束（禁止出现）：

| 类别 | 测什么 |
|------|--------|
| rag_recall | 知识检索能否找到该找到的 |
| rag_precision | 找回的内容对不对题 |
| tool_routing | 模型有没有选对工具 |
| hallucination_guard | 没答案时会不会瞎编 |

### 12.3 评分算法

```
期望关键词命中率 = 命中数 / 期望总数
禁忌词惩罚 = 出现 forbidden_keywords → 整题零分（一票否决）
引用检查 = RAG 类 case 必须带正确引用
```

### 12.4 门禁阈值

| 指标 | 红线 |
|------|------|
| 正确率 | ≥ 75% |
| 引用命中率 | ≥ 80% |
| 幻觉率 | ≤ 15% |
| 失败率 | ≤ 10% |
| 首 Token 延迟 P95 | ≤ 4000ms |

五项全过才绿灯，任一不达标 CI 失败。

### 12.5 当前评测结果

| 指标 | 值 |
|------|-----|
| 综合评分 | 91.40% |
| 检索命中率 | 96.00% |
| 引用覆盖率 | 88.00% |
| 回答忠实度 | 93.50% |
| 平均延迟 | 842ms |
| 失败率 | 0.00% |

---

## 13. 数据库设计

### 13.1 核心表

| 表 | 用途 |
|----|------|
| conversation | 对话历史（tenant + conversation_id + role + message） |
| ingestion_job | 异步入库任务状态 |
| agent_task / agent_step / agent_event | 工作流任务/步骤/事件持久化 |
| memory_item / memory_event | 四层记忆 + 事件溯源 |
| kg_entity / kg_relation / kg_fact | 知识图谱 |
| users / roles / permissions / api_keys / refresh_tokens | 安全体系 |
| audit_log | 审计日志 |
| tenant_budget / tenant_usage_daily | 租户成本治理 |
| model_ab_exposure | 模型 A/B 实验曝光 |
| answer_feedback | 用户反馈 |

### 13.2 迁移管理

Flyway 管理，脚本位于 `src/main/resources/db/migration/`，先迁移后发流量。

---

## 14. API 概览

### 会话与 Agent

```
GET/POST  /ai/chat                          通用问答
POST      /ai/react/chat                    ReAct 同步
POST      /ai/react/chat/stream             ReAct SSE 流式
POST      /ai/workflow/react/chat           工作流 ReAct
POST      /ai/research/tasks                DeepResearch
GET       /ai/workflow/tasks/{taskId}       任务详情
GET       /ai/workflow/tasks/{taskId}/events 事件流
```

### 检索与记忆

```
POST      /ai/rag/search                    混合检索问答
GET       /ai/memory/query                  查询记忆
POST      /ai/memory/save                   保存记忆
GET       /ai/graph/search                  图谱搜索
```

### 知识入库

```
POST      /ai/pdf/upload/{chatId}           PDF 上传
POST      /ingestion/upload/{chatId}        异步入库提交
GET       /ingestion/jobs/{jobId}           任务状态
```

### 鉴权与审计

```
POST      /auth/token                       API Key 换 JWT
POST      /auth/refresh                     刷新 Token
POST      /auth/api-keys                    创建/轮换/吊销
GET       /audit/logs                       审计查询
```

---

## 15. 部署与运维

### 15.1 容器编排

```bash
# 一键启动全栈（应用 + MySQL + Redis + RabbitMQ + 前端）
./scripts/demo.sh

# 独立观察栈
docker compose -f docker-compose.observability.yml up -d
```

### 15.2 CI 流水线（7 阶段）

| Job | 职责 |
|-----|------|
| code-quality | Checkstyle / PMD / SpotBugs |
| build | 编译、单测、集成测试、JaCoCo、SBOM、回归评测 |
| frontend | ESLint / Prettier / vue-tsc / Vite 构建 |
| owasp-scan | OWASP 依赖漏洞扫描 |
| e2e-smoke | 端到端烟测 |
| trivy-scan | 容器镜像扫描 |
| docker | Docker Buildx + GHCR 推送 |

### 15.3 生产检查清单

- [ ] `APP_SECURITY_ENABLED=true`
- [ ] 注入 `APP_JWT_SECRET` 与 `OPENAI_API_KEY`
- [ ] 确认 metrics / logs / trace 接通
- [ ] 执行 `scripts/run_regression.py`
- [ ] 执行压测 `performance/k6/distributed_chat_ingestion.js`

---

## 16. 已知局限与改进方向

### 16.1 上下文窗口管理

**现状**：四层记忆有硬上限（5+10+5 条）不会膨胀，但 `MessageChatMemoryAdvisor` 未显式配置窗口大小，30 轮长对话可能叠加出约 2 万 Token 历史，存在超窗口或注意力稀释风险。缺少发送前 Token 计数与历史摘要压缩。

**改进**：显式配置记忆窗口（如最近 10 轮）；发送前 Token 估算超限则按时间衰减丢弃；对被丢弃历史做一次性摘要压缩。

### 16.2 任务级超时预算

**现状**：ReAct 路径有 MAX_STEPS=4 硬上限，但 DeepResearch 对规划 Agent 生成的子问题数量无上限，理论上可产生不可控的检索轮数。`AgentWorkflowEngine` 只记录任务延迟指标，不强制拦截。

**改进**：创建任务时设定 deadline，每步执行前检查是否超时；给规划 Agent 加子问题数量上限（如 5 个）。

### 16.3 Resilience4j 接入确认

**已解决**：`llm.ModelCallGuard` 已编程式织入全部 LLM 调用链（`ReactAgentService` / `WorkflowReactAgentService` / `RagAnswerService` / `HybridRagAnswerService` / `ResearchPlannerAgent` / `ReportWriterAgent`），同步调用走 Retry+熔断、流式走订阅前快速失败+30s 超时基线；resilience4j starter 自动发布 `resilience4j_circuitbreaker_*` 指标（懒创建实例也能挂上），另有 `llm.call.outcome` 自定义计数器。参数与织入明细见 10.2。

### 16.4 记忆系统与主管线的集成

**现状**：读写闭环已全线打通（详见 `docs/architecture-memory-system.md`）。写侧四类 Recorder：`ChatTurnMemoryRecorder`（对话轮次 short）、`MemoryExtractionService`（画像提取 long）、`TaskConclusionMemoryRecorder`（任务结论 task）、`RagFactMemoryRecorder`（RAG 事实 fact）。读侧 `MemoryInjectionAdvisor` 显式 opt-in：调用方同时传 `memory.tenantId` 与 `memory.userId` 两个 advisor 参数才注入，记忆以独立 SystemMessage 插入 prompt 首部（不改写 user 消息，会话历史不累积记忆快照），默认只注 long/fact 跨会话视图；该 advisor 挂在 `CommonConfiguration` 的四个 ChatClient（主聊天 chatClient、客服 serviceChatClient、PDF 问答 pdfChatClient、agentChatClient）上。`HybridRagAnswerService` 另走显式召回（user prompt 三段式带"已知记忆"段并上报 `memoryUsed`），`ReactAgentService` 在 planner 提示词注入召回快照并经 `ChatTurnMemoryRecorder` 写回。REST 查询与管理由 `MemoryController` 提供（`/ai/memory/**`：query / task / events / save）。

**改进**：无（原"未接入主管线"的评估已过时）；后续可关注 token 估算未计入记忆段的记账瑕疵（HybridRagAnswerService 已知瑕疵注释）。

### 16.5 Spring AI 版本升级

**现状**：已完成从 `1.0.0-M6` 到 `1.1.7` 稳定线（Maven Central）的升级，breaking changes（QuestionAnswerAdvisor 新包路径、`BaseChatMemoryAdvisor` 常量、starter 命名等）已全部适配。

**历史记录**：迁移计划与风险矩阵见 `docs/spring-ai-upgrade-plan.md`（已标记完成，留档备查）。

---

## 附录

### 环境变量速查

| 变量 | 说明 | 默认 |
|------|------|------|
| OPENAI_API_KEY | 模型密钥（必填） | — |
| OPENAI_BASE_URL | OpenAI 兼容网关 | dashscope |
| DB_URL / DB_USERNAME / DB_PASSWORD | MySQL 连接 | localhost |
| APP_SECURITY_ENABLED | 安全开关 | true |
| APP_JWT_SECRET | JWT 签名密钥 | — |
| APP_VECTOR_STORE_BACKEND | pgvector / simple | pgvector |
| APP_INGESTION_QUEUE_BACKEND | redis_stream / rabbitmq / db_polling | redis_stream |
| APP_RATE_LIMIT_CAPACITY | 限流容量 | 60 |
| APP_MODEL_ROUTER_DEFAULT_PROFILE | 默认模型档位 | balanced |
| APP_COST_GOVERNANCE_ENABLED | 成本治理开关 | true |

### 相关文档

- 运维手册：`docs/operations.md`
- 架构说明：`docs/architecture-enterprise.md`
- Agent Harness：`docs/architecture-agent-harness.md`
- 混合检索：`docs/architecture-hybrid-retrieval.md`
- 记忆系统：`docs/architecture-memory-system.md`
- 知识图谱：`docs/architecture-knowledge-graph.md`
- 评测指南：`evaluation/README.md`
- Spring AI 升级计划：`docs/spring-ai-upgrade-plan.md`
