-- R7.3 工具人工确认流程：高危工具（executionConfirm=true）被智能体 tool_call 调用时
-- 先落一条待确认任务，用户在聊天界面审批后才真实执行。
-- status: PENDING → APPROVED → EXECUTED / FAILED；PENDING → CANCELLED / EXPIRED（惰性过期）。
CREATE TABLE agent_tool_confirmation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  agent_id BIGINT NOT NULL,
  chat_id VARCHAR(128) NOT NULL,
  tool_asset_id BIGINT NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  arguments_json TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  result_summary TEXT NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  decided_at DATETIME NULL,
  expires_at DATETIME NOT NULL,
  INDEX idx_atc_tenant_chat (tenant_id, chat_id, status),
  INDEX idx_atc_expires (status, expires_at)
);
