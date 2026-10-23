-- 租户模型演进：从"注册用户一人一租户（u-<用户名>）"转为"公司内部部署形态"——
-- 全公司共用单一公司租户 public（管理员 bootstrap key 同租户），
-- 注册用户一律加入公司租户，管理员天然可见可管全部公司资产。
--
-- tenant_id 行级隔离机制原样保留（所有表的租户过滤、query_database 租户占位符、
-- 身份定租户防请求头伪造均不变）：未来多公司 SaaS 化只需引入组织租户，属增量演进。
--
-- 本迁移把存量身份类数据（用户 / API Key / 刷新令牌）从个人租户迁入公司租户：
-- 旧个人租户名下的业务数据（会话 / 评测集 / 文档等）不迁移、保持休眠，可按需清理；
-- audit_log 历史记录保留原租户值（审计历史不做追溯改写）。
UPDATE users
SET tenant_id = 'public', updated_at = NOW()
WHERE tenant_id LIKE 'u-%';

UPDATE api_keys
SET tenant_id = 'public'
WHERE tenant_id LIKE 'u-%';

UPDATE refresh_tokens
SET tenant_id = 'public'
WHERE tenant_id LIKE 'u-%';
