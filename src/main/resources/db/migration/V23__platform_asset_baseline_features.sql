CREATE TABLE IF NOT EXISTS platform_asset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  asset_type VARCHAR(32) NOT NULL,
  parent_id BIGINT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  config_json LONGTEXT NULL,
  created_by VARCHAR(128) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_platform_asset_tenant_type_name (tenant_id, asset_type, name),
  INDEX idx_platform_asset_tenant_type_parent (tenant_id, asset_type, parent_id),
  INDEX idx_platform_asset_updated (updated_at)
);
