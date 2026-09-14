# 企业架构说明

## 1. 限界上下文

- 会话上下文：聊天、记忆、历史查询
- 知识上下文：上传、解析、切片、向量化、检索
- 安全上下文：API Key 生命周期、JWT、权限校验
- 运维上下文：指标、日志、链路追踪、告警、演练

## 2. 数据归属

- MySQL:
  - `conversation`（聊天历史）
  - `ingestion_job`（异步任务状态）
  - `users/roles/permissions/api_keys/audit_log`
  - 业务表（`course/school/course_reservation`）
- 向量存储：
  - 知识切片与元数据
- 本地或对象存储：
  - 上传的原始文件

## 3. 可靠性设计

- 通过 `X-Idempotency-Key` 实现幂等入库
- 重试次数有上限并带延迟退避
- 终态失败写入 DLQ
- 队列后端抽象（Redis Stream / RabbitMQ）

## 4. 安全设计

- API Key 用于机器访问引导
- JWT 用于请求级认证/授权
- Refresh Token 轮换
- 租户级 API Key 生命周期（`X-Tenant-Id`）
- tenant + principal 复合维度限流
- RBAC + 路由级权限校验
- 审计日志保留策略定时任务

## 5. 可扩展性设计

- 无状态 API 实例可水平扩展
- 异步入库解耦上传与向量化成本
- 向量后端可从本地 simple 切换到 pgvector
- 模型路由支持按 profile 的成本/质量路由
- 可观测性栈支持饱和度与错误趋势诊断

## 6. 运维建议

- 客户端保持 `chatId` 稳定，以保证会话连续性
- 为聊天与入库端点设置独立限流
- 依赖升级前后定期执行回归测试
- 告警阈值与代码一同纳入版本管理
