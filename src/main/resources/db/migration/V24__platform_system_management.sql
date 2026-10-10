-- 平台系统管理元数据：内置角色不允许改名/删除；角色描述仅管理展示，
-- 权限仍复用 permissions + role_permissions，避免引入第二套鉴权模型。
ALTER TABLE users
  ADD COLUMN display_name VARCHAR(64) NULL;

ALTER TABLE roles
  ADD COLUMN description VARCHAR(512) NULL,
  ADD COLUMN builtin TINYINT(1) NOT NULL DEFAULT 0;

UPDATE roles SET description = '平台内置管理员角色', builtin = 1 WHERE role_name = 'ADMIN';
UPDATE roles SET description = '平台内置普通用户角色', builtin = 1 WHERE role_name = 'USER';
UPDATE roles SET description = '平台内置运维角色', builtin = 1 WHERE role_name = 'OPS';

-- 前端角色授权树使用的语义权限目录；是否生效仍由 SecurityConfiguration/@PreAuthorize 决定。
INSERT IGNORE INTO permissions (permission_name, created_at) VALUES
  ('chat:use', NOW()),
  ('chat:agent-debug', NOW()),
  ('agent:read', NOW()),
  ('agent:write', NOW()),
  ('agent:publish', NOW()),
  ('workflow:read', NOW()),
  ('workflow:write', NOW()),
  ('workflow:run', NOW()),
  ('tool:read', NOW()),
  ('tool:write', NOW()),
  ('tool:execute', NOW()),
  ('knowledge:read', NOW()),
  ('knowledge:write', NOW()),
  ('platform:model', NOW()),
  ('platform:safety', NOW()),
  ('platform:database', NOW()),
  ('system:user', NOW()),
  ('system:role', NOW()),
  ('system:audit', NOW());
