CREATE TABLE IF NOT EXISTS user_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_hash VARCHAR(64) NOT NULL,
  principal VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  roles_csv VARCHAR(255) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked_at DATETIME NULL,
  UNIQUE KEY uk_user_session_hash (session_hash),
  INDEX idx_user_session_principal (tenant_id, principal, expires_at),
  INDEX idx_user_session_expires (expires_at)
);
