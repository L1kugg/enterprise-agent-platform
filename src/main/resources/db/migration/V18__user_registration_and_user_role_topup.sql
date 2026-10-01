-- Self-registration groundwork (users table itself dates back to V1 and
-- was never referenced by Java code until now).
--
-- 1) users.tenant_id: every registered user owns a private tenant
--    ('u-' + lowercase username), so vector / retrieval / session /
--    cost isolation keeps working unchanged per user. The admin
--    bootstrap key stays in tenant 'public' (V6 default preserved).
-- 2) USER role permission top-ups so a registered user can reach every
--    console feature. chat:write / chat:read / ingestion:write /
--    ingestion:read / rag:read were granted in V1 / V4 / V5; the
--    evaluation studio and the own-tenant cost summary were not.

ALTER TABLE users
  ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'public';

INSERT IGNORE INTO permissions (permission_name, created_at) VALUES
  ('eval:read', NOW()),
  ('eval:write', NOW()),
  ('cost:read', NOW());

INSERT IGNORE INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM roles r
INNER JOIN permissions p ON (
  r.role_name = 'USER'
  AND p.permission_name IN ('eval:read', 'eval:write', 'cost:read')
);
