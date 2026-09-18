# 企业级架构说明

## 1. 限界上下文

- 会话上下文（Conversation Context）：聊天、记忆、历史查询
- 知识上下文（Knowledge Context）：上传、解析、分块、向量化、检索
- 安全上下文（Security Context）：API Key 生命周期、JWT、权限校验
- 运维上下文（Operations Context）：指标、日志、链路追踪、告警、演练

## 2. 数据归属

- MySQL：
  - `conversation`（聊天历史）
  - `ingestion_job`（异步任务状态）
  - `users/roles/permissions/api_keys/audit_log`
  - 业务表（`course/school/course_reservation`）
- 向量存储：
  - 知识 chunk 及其元数据
- 本地或对象存储：
  - 原始上传文件

## 3. 可靠性设计

- 通过 `X-Idempotency-Key` 实现幂等摄取
- 重试次数有上限，并采用延迟退避
- 终态失败进入 DLQ
- 队列后端抽象（Redis Stream / RabbitMQ）

## 4. 安全设计

- API Key 用于机器访问引导
- JWT 用于请求级认证/授权
- Refresh token 轮换
- 基于已认证身份派生的租户级 API Key 生命周期管理
- 租户 + 主体复合限流（单实例内存桶）
- RBAC + 路由级权限校验
- 审计日志按保留期定时清理

## 5. 可扩展性设计

- 无状态 API 实例可水平扩展
- 异步摄取将上传与向量化成本解耦
- 向量后端可从本地/简单实现切换为 pgvector
- 模型路由器支持按 profile 做成本/质量路由
- 可观测性栈支持饱和度与错误趋势诊断

## 6. 运维建议

- 客户端保持 `chatId` 稳定，以维持对话连续性
- 为聊天端点和摄取端点设置各自独立的限流
- 在水平扩展受保护端点之前，先切换到共享限流后端
- 依赖升级前后定期执行回归测试
- 告警阈值与代码一同纳入版本管理
