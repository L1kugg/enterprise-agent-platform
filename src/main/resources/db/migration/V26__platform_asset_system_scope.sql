-- 平台系统级共享资产（设计见 docs/platform-system-assets-design.md）：
-- model-services / databases 两类凭据型资产支持 scope=SYSTEM（系统共享，全体租户运行时可见），
-- 其余六类业务资产维持租户隔离（应用层白名单校验，越权建 SYSTEM 直接 400）。
-- SYSTEM 行固定落在 public 主租户（唯一键 (tenant_id, asset_type, name) 天然防系统资源重名）。

ALTER TABLE platform_asset
  ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TENANT' AFTER asset_type,
  ADD INDEX idx_platform_asset_scope (asset_type, scope);

-- 存量标记：public 主租户的两类凭据资产视为系统资源（各租户已有的重复行保留为租户私有，
-- 系统共享生效后由管理员在界面自行清理）
UPDATE platform_asset SET scope = 'SYSTEM'
WHERE tenant_id = 'public' AND asset_type IN ('model-services', 'databases');
