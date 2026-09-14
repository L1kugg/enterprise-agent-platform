# 企业部署指南

## 1. 目标拓扑

推荐的生产基线配置：

- API 服务：2-3 个无状态实例
- MySQL：主从或托管高可用版本
- Redis：哨兵/集群模式
- RabbitMQ：镜像队列或托管消息服务
- 向量存储：PostgreSQL + pgvector（独立实例）
- 可观测性：Prometheus + Loki + Tempo + Alertmanager

## 2. 必需环境变量

必填项：

- `OPENAI_API_KEY`
- `APP_JWT_SECRET`（32 字节以上）
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`

强烈建议：

- `APP_SECURITY_ENABLED=true`
- `APP_RATE_LIMIT_ENABLED=true`
- `APP_MODEL_ROUTER_ENABLED=true`
- `APP_MODEL_ROUTER_DEFAULT_PROFILE=balanced`
- `APP_VECTOR_STORE_BACKEND=pgvector`
- `APP_REQUIRE_PGVECTOR=true`

## 3. 发布顺序

1. 构建镜像：
   - `docker build -t knowledgeops-agent:<tag> .`
2. 执行数据库迁移（由 Flyway 在启动时执行，或在流水线阶段执行）。
3. 部署金丝雀实例。
4. 验证：
   - `/actuator/health`
   - `/actuator/prometheus`
   - 关键 API（`/ai/chat`、`/ai/pdf/chat`、`/auth/token`）
5. 逐步切流。
6. 执行部署后冒烟测试与回归测试。

## 4. 回滚策略

- 保留上一版本镜像 tag 处于可用状态。
- 优先回滚服务镜像。
- 涉及 schema 变更时，发布前确保迁移向后兼容。
- 队列堆积激增时，暂停入库消费者并逐步消化积压。

## 5. SLO 建议

- Chat API 可用性：>= 99.9%
- `/ai/chat` p95 延迟：<= 1500 ms
- 入库失败率（5 分钟窗口）：<= 5%
- critical 告警 MTTR：<= 30 分钟

## 6. 生产前检查清单

- [ ] 密钥已从 Vault/KMS/Secret Manager 加载
- [ ] API Key 签发/吊销流程已验证
- [ ] JWT 刷新流程已验证
- [ ] 入库重试 + DLQ 已验证
- [ ] 仪表盘与告警路由已验证
- [ ] 压测基线已记录
- [ ] 备份与恢复已演练（MySQL + 向量存储）
