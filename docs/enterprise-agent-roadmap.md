# KnowledgeOps Agent 企业化改造路线图

> 基于 2026-10-07 完成的第一阶段改造（去教育化 + 企业身份）后的后续规划。
> 每个阶段标注优先级、预估工作量、涉及文件和验收标准。

---

## 已完成：第一阶段（身份改造）

| 改动 | 文件 |
|---|---|
| 移除 query_school / query_course / add_course_reservation | ActionSchemaRegistry, BuiltinToolRuntime |
| 新增 create_task（创建工作任务） | ActionSchemaRegistry, BuiltinToolRuntime |
| 规划器身份："教育助手" → "企业智能助手" | WorkflowReactAgentService, ReactAgentService |
| 系统提示词：删除"客服小星"话术，替换为企业助手身份 | SystemConstants |
| Fallback：校区→自我介绍，课程预约→任务创建 | ReactPlannerFallbacks |
| 通用 ChatClient 系统提示词更新 | CommonConfiguration |
| 测试同步更新（4 个测试文件） | ActionPolicyGuardTest 等 |

---

## 第二阶段：动作插件化架构（P0 · 1-2 周）

### 目标

新增企业工具集成时**不需要改引擎代码**，只需写一个 Provider 类。

### 改动

**1. 新建 ActionProvider 接口**

```java
// src/main/java/com/enterprise/iqk/agent/harness/ActionProvider.java
public interface ActionProvider {
    /** 本 Provider 能提供哪些动作 */
    List<ActionSchema> actions();

    /** 执行动作 */
    AgentObservation execute(AgentAction action);

    /** 在哪个运行时注册（builtin / mcp / custom） */
    String runtimeName();
}
```

**2. 改造 ActionSchemaRegistry 为动态发现**

```java
@Component
public class ActionSchemaRegistry {
    private final Map<String, ActionSchema> schemas = new LinkedHashMap<>();

    public ActionSchemaRegistry(List<ActionProvider> providers) {
        providers.forEach(p -> p.actions().forEach(this::register));
    }
}
```

**3. BuiltinToolRuntime 拆分为多个 Provider**

| 现有内置动作 | 拆分后的 Provider |
|---|---|
| create_task | TaskActionProvider |
| rag_search | RagActionProvider |
| query_database | DataQueryActionProvider |

**涉及文件**：
- 新建 `ActionProvider.java`
- 改造 `ActionSchemaRegistry.java`
- 新建 `TaskActionProvider.java`、`RagActionProvider.java`、`DataQueryActionProvider.java`
- 简化 `BuiltinToolRuntime.java`（变为路由分发器）

**验收标准**：新增一个 `WeatherActionProvider` 类，不改任何引擎代码，规划器自动看到天气动作并能调用。

---

## 第三阶段：企业系统集成（P0-P1 · 按需逐个接入）

每个集成 = 一个 ActionProvider + 一个配置段。按优先级排序：

### 3.1 企业文档搜索（P0 · 3 天）

```java
public class ConfluenceActionProvider implements ActionProvider {
    // 动作：search_pages, get_page_content
    // 数据源：Confluence REST API / SharePoint Graph API
    // 配置：app.integration.confluence.base-url / token / space-key
}
```

| 配置项 | 说明 |
|---|---|
| `APP_INTEGRATION_CONFLUENCE_BASE_URL` | Confluence 地址 |
| `APP_INTEGRATION_CONFLUENCE_TOKEN` | API Token |
| `APP_INTEGRATION_CONFLUENCE_SPACE_KEY` | 默认搜索空间 |

### 3.2 IM 消息通知（P0 · 2 天）

```java
public class ImNotifyActionProvider implements ActionProvider {
    // 动作：send_notification
    // 支持：钉钉 Webhook / 企业微信 / Slack
    // 配置：app.integration.im.webhook-url / type
}
```

| 配置项 | 说明 |
|---|---|
| `APP_INTEGRATION_IM_TYPE` | dingtalk / wecom / slack |
| `APP_INTEGRATION_IM_WEBHOOK_URL` | Webhook 地址 |

### 3.3 工单系统（P1 · 3 天）

```java
public class JiraActionProvider implements ActionProvider {
    // 动作：create_issue, query_issues, update_status
    // 数据源：Jira REST API v3
}
```

### 3.4 日历集成（P1 · 2 天）

```java
public class CalendarActionProvider implements ActionProvider {
    // 动作：check_availability, book_meeting
    // 数据源：Google Calendar API / Outlook Graph API
}
```

### 3.5 CRM 查询（P2 · 按需）

```java
public class CrmActionProvider implements ActionProvider {
    // 动作：query_customer, query_opportunity
    // 数据源：Salesforce / 纷享销客 API
}
```

---

## 第四阶段：多场景智能路由（P1 · 1 周）

### 目标

不同类型的问题走不同的处理管线，而不是所有问题都走同一个 ReAct 循环。

### 架构

```
用户输入
  ↓
意图分类器（economy 模型，<500ms）
  ├─ 知识问答     → RAG 管线（现有 HybridRagAnswerService）
  ├─ 业务操作     → ReAct 工具调用（现有引擎）
  ├─ 数据分析     → SQL 生成 + query_database
  ├─ 文档写作     → 写作模式（需新增）
  ├─ 多步研究     → DeepResearch（现有）
  └─ 闲聊/自我    → 直接回答
```

### 改动

**新建 IntentClassifier**

```java
@Component
public class IntentClassifier {
    public enum Intent { RAG_QA, TOOL_CALL, DATA_ANALYSIS, WRITING, RESEARCH, CHAT }

    public Intent classify(String prompt) {
        // 用 economy 模型做轻量分类，返回意图标签
    }
}
```

**改造 ChatController**

根据 Intent 路由到不同处理链路。

**涉及文件**：
- 新建 `IntentClassifier.java`
- 改造 `ChatController.java` / `ReactController.java`
- 新建 `WritingService.java`（文档写作模式）

---

## 第五阶段：生产加固（P0 · 立即做）

### 5.1 会话自动云同步（P0 · 半天）

**现状**：对话只存 localStorage，点"保存到云端"才推到服务器。

**改法**：在 `useChatEngine.ts` 的 `ask()` 完成回调里自动调 `syncActiveSessionToCloud()`。

**涉及文件**：`frontend/src/composables/useChatEngine.ts`

### 5.2 上下文窗口自适应（P1 · 2 天）

**现状**：rollingContext 截断到 8000 字符（固定值），ChatMemory 窗口 20 条。

**改法**：根据模型档位动态调整：
- economy（8K tokens）→ rollingContext 4000 字符，ChatMemory 10 条
- balanced（32K）→ rollingContext 8000 字符，ChatMemory 20 条
- quality（32K）→ rollingContext 12000 字符，ChatMemory 30 条

**涉及文件**：`AgentWorkflowProperties`、`MysqlChatMemory`、`ReactResponseFormatter`

### 5.3 企业评测数据集（P1 · 1 天）

**现状**：评测集里有课程相关测试题。

**改法**：新增企业场景评测集：
- 知识问答准确性（基于上传的企业文档）
- 业务数据查询正确性（SQL 生成 + 结果准确性）
- 任务创建完整性（字段是否齐全）
- 多轮对话连贯性（跨会话记忆召回）

**涉及文件**：`evaluation/` 目录新增 JSON 数据集

### 5.4 README / 文档更新（P1 · 半天）

**现状**：README 描述为"教育助手"。

**改法**：更新项目定位、功能说明、快速开始指南中的教育相关描述。

---

## 优先级总览

| 阶段 | 优先级 | 预估时间 | 核心价值 |
|---|---|---|---|
| 5.1 会话自动云同步 | **P0** | 半天 | 数据不丢 |
| 5.2 上下文自适应 | P1 | 2 天 | 长对话不炸 |
| 2 动作插件化 | **P0** | 1-2 周 | 新增工具零代码改动 |
| 3.1 文档搜索集成 | **P0** | 3 天 | 企业最刚需 |
| 3.2 IM 通知 | P0 | 2 天 | 审批提醒/异常告警 |
| 4 智能路由 | P1 | 1 周 | 分类精准、体验提升 |
| 3.3-3.5 工单/日历/CRM | P1-P2 | 按需 | 按客户需求接入 |
| 5.3 评测数据集 | P1 | 1 天 | 质量回归 |
| 5.4 文档更新 | P1 | 半天 | 项目形象 |

---

## 技术决策记录

| 决策 | 理由 |
|---|---|
| ActionProvider 用接口而非继承 | Spring 自动发现所有实现，新增 Provider 无需改注册表 |
| IM 通知用 Webhook 而非 API | 零依赖，不需要 OAuth，运维只需贴一个 URL |
| 意图分类用 economy 模型 | 延迟 <500ms，成本极低，分类任务不需要强推理 |
| 会话自动云同步用 debounce | 避免每条消息都调 API，等回答完成后再推 |
| 上下文自适应按模型档位 | 不同模型窗口大小不同，硬编码一个值要么浪费要么溢出 |
