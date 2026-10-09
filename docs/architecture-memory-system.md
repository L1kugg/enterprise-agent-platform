# 长短期记忆系统设计

> 当前状态：读写闭环已全线接入 —— 写侧（对话轮次 short / 画像提取 long / 任务结论 task / RAG 事实 fact）与读侧（全部 7 条生成链路，见下方“各链路注入现状”）均已打通；REST 查询/管理端点见 `MemoryController`（`/ai/memory/**`）。user 键 = 认证主体（匿名回落 chatId），画像跨会话生效。
> 两轮 review 整改的完整问题清单（根因/修法/验证/面试话术）见 [memory-review-fixes.md](memory-review-fixes.md)。

## 四层记忆模型

```
┌─────────────────────────────────────────────┐
│ short_memory  会话最近 N 轮，TTL 24h          │
├─────────────────────────────────────────────┤
│ long_memory   用户画像/偏好/目标，跨会话持久化  │
├─────────────────────────────────────────────┤
│ task_memory   某次研究/客服任务的中间结论       │
├─────────────────────────────────────────────┤
│ fact_memory   可引用、可追溯的事实，带置信度     │
└─────────────────────────────────────────────┘
```

## 设计决策

### 单表继承 vs 多表
所有记忆类型共用 `memory_item` 表，通过 `type` 字段区分。好处：
- 不需要多表 JOIN
- 统一过期管理
- 扩展新记忆类型只需加 type 枚举值

### 何时分表
当某种记忆类型数据量超过千万级，或有特殊索引需求时，可按 type 分表。

## 数据库设计

### memory_item
| 字段 | 说明 |
|---|---|
| memory_id | 全局唯一记忆 ID |
| type | short / long / task / fact |
| content | 记忆内容 |
| source | 来源标注（会话 ID、任务 ID、文档来源） |
| source_task_id | 关联任务 ID（task_memory 专用） |
| confidence | 可信度 (0-1)，低于阈值的不会进入生成上下文 |
| expires_at | 过期时间，NULL 表示永不过期 |
| metadata_json | 扩展元数据 |

### memory_event
| 字段 | 说明 |
|---|---|
| action | CREATE / UPDATE / DELETE / EXPIRE / HIT / USE |
| reason | 操作原因 |

## MemoryService API

| 方法 | 说明 |
|---|---|
| `saveShortMemory()` | 保存短期记忆，默认 TTL 24h |
| `saveLongMemory()` | 保存长期记忆，永不过期 |
| `saveTaskMemory()` | 保存任务记忆，关联 task_id，TTL 30d |
| `saveFactMemory()` | 保存事实记忆，带置信度 |
| `queryShortMemory()` | 查询最近 N 条短期记忆 |
| `queryLongMemory()` | 查询用户长期记忆 |
| `queryTaskMemory()` | 按 taskId 查询任务记忆 |
| `queryFactMemory()` | 按置信度阈值查询事实 |
| `buildContext()` | 按当前问题构建记忆上下文：候选池经相关度、时间衰减与置信度排序后注入 |
| `buildContext(…, includeShort)` | 可裁剪变体：已挂 ChatMemory 的链路跳过 short 层，避免同信息双份 |
| `cleanExpiredMemories()` | 定时清理过期记忆（每天 3am） |

## 目标记忆写入时机

| 时机 | 记忆类型 | 内容 |
|---|---|---|
| 每轮对话结束 | short | 本轮用户问题 + 系统回答摘要 |
| 用户首次注册/配置 | long | 用户画像、偏好、学习目标 |
| Agent 任务完成 | task | 研究中间结论、客服处理方案 |
| RAG 检索到高置信文档 | fact | 抽取的实体-关系-值三元组 |

## 目标记忆召回流程

1. 用户发起请求
2. `buildContext(tenantId, userKey, query)` 检索相关记忆（user 键 = 认证主体，`UserContext.currentUserId(fallback)` 从 SecurityContext 解析，匿名回落 chatId —— 画像按人存、跨会话可召回）。排序公式为 `relevance × 0.60~0.70 + confidence × 0.15~0.25 + recency × 0.05~0.25`；short/fact 设置词面相关度下限，long 保留用户画像基线，各层有注入条数与字符预算。
3. 召回内容注入 LLM 上下文。两种方式：
   - **advisor 注入（推荐）**：`MemoryInjectionAdvisor` 在请求组装期把记忆快照作为独立 SystemMessage 插入 prompt 首部 —— 不改写 user 消息文本，MessageChatMemoryAdvisor 持久化的仍是原始对话，会话历史不会逐轮累积记忆段；每次调用重新召回，注入的永远是最新的唯一一份。调用方以 advisor 参数 `memory.tenantId` + `memory.userId` 显式 opt-in，未传参的链路零影响
   - **手工拼段（react/评测链路）**：拼进 user prompt 末尾的"已知记忆"段。这两条链路无 ChatMemory，拼进 user prompt 不会被持久化重放，无累积问题
4. 记忆命中的观测标记：`memoryUsed`（HybridRagResult / ReactChatResponseVO）或链路日志
5. 本轮结束后写入新的 short memory（user 键对齐读侧）

## 各链路注入现状（读侧闭环）

| 链路 | 注入内容 | 注入方式 | 说明 |
|---|---|---|---|
| 混合 RAG（评测） | short + long + fact | 手工拼段 | HybridRagAnswerService，先例实现 |
| chat（/ai/chat） | long + fact | advisor 注入 | 已挂 ChatMemory advisor，short 层跳过防双份 |
| react（/ai/react/chat） | short + long + fact | 手工拼段 | planner 无 ChatMemory，全量注入规划与成稿 prompt；成稿后写回 short（读写双侧闭环），响应带 memoryUsed |
| 客服（/ai/service） | long + fact | advisor 注入 | serviceChatClient 默认 advisor |
| PDF RAG（/ai/pdf/chat） | long + fact | advisor 注入 | RagAnswerService 生成调用传参 |
| 工作流 ReAct（v2） | short + long + fact | 手工拼段 | 循环外按当前 prompt 一次召回并注入 planner prompt，避免 advisor 与手工拼段重复注入同一批记忆 |
| 深度研究（/ai/research） | task 层结论 | planner prompt 注入 | DeepResearchService 召回租户内最近 5 条任务结论给拆题参考，避免重复已解决的问题 |

## 自动过期

```sql
DELETE FROM memory_item
WHERE expires_at IS NOT NULL AND expires_at < NOW()
```

由 Spring `@Scheduled(cron = "0 0 3 * * ?")` 每天凌晨 3 点执行。
