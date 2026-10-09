# 企业级部署指南

## 1. 目标拓扑

生产环境推荐基线：

- API 服务：2-3 个无状态实例
- MySQL：托管高可用或主从架构
- Redis：哨兵/集群模式
- RabbitMQ：镜像队列或托管 MQ
- 向量存储：PostgreSQL + pgvector（独立部署）
- 可观测性：Prometheus + Loki + Tempo + Alertmanager

## 2. 必需的环境变量

必填：

- `OPENAI_API_KEY`
- `APP_JWT_SECRET` (32+ bytes)
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

## 3. 发布流程

1. 构建镜像：
   - `docker build -t enterprise-agent-platform:<tag> .`
2. 执行数据库迁移（启动时由 Flyway 完成或在流水线阶段执行）。
3. 部署金丝雀实例。
4. 验证：
   - `/actuator/health`
   - `/actuator/prometheus`
   - 关键 API（`/ai/chat`、`/ai/pdf/chat`、`/auth/token`）
5. 逐步切流。
6. 执行发布后冒烟测试与回归测试。

## 4. 回滚策略

- 保留上一个镜像 tag 随时可用。
- 优先回滚服务镜像。
- 涉及 schema 变更时，务必在发布前确保迁移向后兼容。
- 如果队列积压激增，暂停摄取消费者并逐步消化积压。

## 5. SLO 建议

- 聊天 API 可用性：>= 99.9%
- `/ai/chat` p95 延迟：<= 1500 ms
- 摄取失败率（5 分钟）：<= 5%
- 关键告警 MTTR：<= 30 分钟

## 6. 上线前检查清单

- [ ] 密钥已从 Vault/KMS/Secret Manager 加载
- [ ] API Key 签发/吊销流程已验证
- [ ] JWT 刷新流程已验证
- [ ] 摄取重试 + DLQ 已验证
- [ ] 仪表盘和告警路由已验证
- [ ] 压测基线已记录
- [ ] 备份与恢复已演练（MySQL + 向量存储）
