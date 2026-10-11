-- 智能体运行时绑定关系表（需求 R1.3）：把 config_json 里的绑定 ID 落成关系表，
-- 运行时只读表，config_json 保留标量配置（提示词/参数/开场白等）。
-- 六张绑定表结构统一，resource_type 冗余存储便于统一查询。

CREATE TABLE IF NOT EXISTS agent_model_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'model-services',
  binding_order INT NOT NULL DEFAULT 1,
  execution_confirm TINYINT(1) NOT NULL DEFAULT 1,
  extra_json TEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_model (tenant_id, agent_id, resource_id),
  INDEX idx_agent_model_agent (tenant_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_workflow_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'workflows',
  binding_order INT NOT NULL DEFAULT 1,
  execution_confirm TINYINT(1) NOT NULL DEFAULT 1,
  extra_json TEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_workflow (tenant_id, agent_id, resource_id),
  INDEX idx_agent_workflow_agent (tenant_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_tool_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'tools',
  binding_order INT NOT NULL DEFAULT 1,
  execution_confirm TINYINT(1) NOT NULL DEFAULT 1,
  extra_json TEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_tool (tenant_id, agent_id, resource_id),
  INDEX idx_agent_tool_agent (tenant_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_knowledge_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'knowledge-bases',
  binding_order INT NOT NULL DEFAULT 1,
  execution_confirm TINYINT(1) NOT NULL DEFAULT 1,
  extra_json TEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_knowledge (tenant_id, agent_id, resource_id),
  INDEX idx_agent_knowledge_agent (tenant_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_safety_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'safety-guards',
  binding_order INT NOT NULL DEFAULT 1,
  execution_confirm TINYINT(1) NOT NULL DEFAULT 1,
  extra_json TEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_safety (tenant_id, agent_id, resource_id),
  INDEX idx_agent_safety_agent (tenant_id, agent_id)
);

-- 发布渠道：resource_id 恒为 0（渠道是字符串，靠 channel 列承载），唯一键走 channel
CREATE TABLE IF NOT EXISTS agent_publish_channel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL DEFAULT 0,
  resource_type VARCHAR(32) NOT NULL DEFAULT 'publish-channel',
  binding_order INT NOT NULL DEFAULT 1,
  execution_confirm TINYINT(1) NOT NULL DEFAULT 1,
  channel VARCHAR(64) NOT NULL,
  extra_json TEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_agent_channel (tenant_id, agent_id, channel),
  INDEX idx_agent_channel_agent (tenant_id, agent_id)
);

-- 智能体调用日志：FR-R1-01 会话可追溯到智能体 + R2.2 看板数据源
CREATE TABLE IF NOT EXISTS agent_invocation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  agent_id BIGINT NOT NULL,
  user_id VARCHAR(128) NOT NULL DEFAULT '',
  chat_id VARCHAR(128) NOT NULL DEFAULT '',
  channel VARCHAR(16) NOT NULL DEFAULT 'platform',
  status VARCHAR(16) NOT NULL,
  model_name VARCHAR(128) NULL,
  fallback_applied TINYINT(1) NOT NULL DEFAULT 0,
  latency_ms BIGINT NULL,
  prompt_excerpt VARCHAR(512) NULL,
  error_summary VARCHAR(512) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_invocation_agent (tenant_id, agent_id, created_at),
  INDEX idx_invocation_chat (tenant_id, chat_id)
);

-- 存量数据迁移：config_json 里的绑定 ID 迁入关系表（INSERT IGNORE 配合唯一键幂等）。
-- MySQL 8.0 语法要点（生产 8.0.36 实测）：
--   * JSON_TABLE COLUMNS 不认 BOOLEAN 列类型，用 INT（JSON 布尔抽取为 1/0）；
--   * 空值处理只能写 NULL ON ERROR，DEFAULT NULL 不是合法语法；
--   * JSON_TABLE 的 JSON 参数只能引用基表列，引用派生表/CTE 列报 1210，
--     所以守卫条件（asset_type/JSON_VALID）写在 WHERE 里，配合应用层写入必为合法 JSON。
-- tools[].id 曾存 -1 哨兵，统一过滤 <= 0。

INSERT IGNORE INTO agent_model_binding
  (tenant_id, agent_id, resource_id, resource_type, binding_order, execution_confirm, created_at, updated_at)
SELECT a.tenant_id, a.id, j.model_id, 'model-services', 1,
       CASE WHEN j.exec_confirm = 0 THEN 0 ELSE 1 END, NOW(), NOW()
FROM platform_asset a
JOIN JSON_TABLE(a.config_json,'$' COLUMNS (
       model_id BIGINT PATH '$.modelId' NULL ON ERROR,
       exec_confirm INT PATH '$.executionConfirm' NULL ON ERROR
     )) j
WHERE a.asset_type = 'agents' AND a.config_json IS NOT NULL AND JSON_VALID(a.config_json)
  AND j.model_id IS NOT NULL AND j.model_id > 0;

INSERT IGNORE INTO agent_workflow_binding
  (tenant_id, agent_id, resource_id, resource_type, binding_order, execution_confirm, created_at, updated_at)
SELECT a.tenant_id, a.id, j.wf_id, 'workflows', j.ord,
       CASE WHEN j.exec_confirm = 0 THEN 0 ELSE 1 END, NOW(), NOW()
FROM platform_asset a
JOIN JSON_TABLE(a.config_json,'$.workflowIds[*]' COLUMNS (
       ord FOR ORDINALITY,
       wf_id BIGINT PATH '$' NULL ON ERROR,
       exec_confirm INT PATH '$.executionConfirm' NULL ON ERROR
     )) j
WHERE a.asset_type = 'agents' AND a.config_json IS NOT NULL AND JSON_VALID(a.config_json)
  AND j.wf_id IS NOT NULL AND j.wf_id > 0;

INSERT IGNORE INTO agent_tool_binding
  (tenant_id, agent_id, resource_id, resource_type, binding_order, execution_confirm, extra_json, created_at, updated_at)
SELECT a.tenant_id, a.id, j.tool_id, 'tools', j.ord,
       CASE WHEN j.exec_confirm = 0 THEN 0 ELSE 1 END,
       j.tool_desc, NOW(), NOW()
FROM platform_asset a
JOIN JSON_TABLE(a.config_json,'$.tools[*]' COLUMNS (
       ord FOR ORDINALITY,
       tool_id BIGINT PATH '$.id' NULL ON ERROR,
       tool_desc TEXT PATH '$.description' NULL ON ERROR,
       exec_confirm INT PATH '$.executionConfirm' NULL ON ERROR
     )) j
WHERE a.asset_type = 'agents' AND a.config_json IS NOT NULL AND JSON_VALID(a.config_json)
  AND j.tool_id IS NOT NULL AND j.tool_id > 0;

INSERT IGNORE INTO agent_knowledge_binding
  (tenant_id, agent_id, resource_id, resource_type, binding_order, execution_confirm, created_at, updated_at)
SELECT a.tenant_id, a.id, j.kb_id, 'knowledge-bases', j.ord,
       CASE WHEN j.exec_confirm = 0 THEN 0 ELSE 1 END, NOW(), NOW()
FROM platform_asset a
JOIN JSON_TABLE(a.config_json,'$.knowledgeBaseIds[*]' COLUMNS (
       ord FOR ORDINALITY,
       kb_id BIGINT PATH '$' NULL ON ERROR,
       exec_confirm INT PATH '$.executionConfirm' NULL ON ERROR
     )) j
WHERE a.asset_type = 'agents' AND a.config_json IS NOT NULL AND JSON_VALID(a.config_json)
  AND j.kb_id IS NOT NULL AND j.kb_id > 0;

INSERT IGNORE INTO agent_safety_binding
  (tenant_id, agent_id, resource_id, resource_type, binding_order, execution_confirm, created_at, updated_at)
SELECT a.tenant_id, a.id, j.sg_id, 'safety-guards', j.ord,
       CASE WHEN j.exec_confirm = 0 THEN 0 ELSE 1 END, NOW(), NOW()
FROM platform_asset a
JOIN JSON_TABLE(a.config_json,'$.safetyGuardIds[*]' COLUMNS (
       ord FOR ORDINALITY,
       sg_id BIGINT PATH '$' NULL ON ERROR,
       exec_confirm INT PATH '$.executionConfirm' NULL ON ERROR
     )) j
WHERE a.asset_type = 'agents' AND a.config_json IS NOT NULL AND JSON_VALID(a.config_json)
  AND j.sg_id IS NOT NULL AND j.sg_id > 0;

INSERT IGNORE INTO agent_publish_channel
  (tenant_id, agent_id, resource_id, resource_type, binding_order, execution_confirm, channel, created_at, updated_at)
SELECT a.tenant_id, a.id, 0, 'publish-channel', c.ord, 1, c.ch, NOW(), NOW()
FROM platform_asset a
JOIN JSON_TABLE(a.config_json,'$.publishChannels[*]' COLUMNS (
       ord FOR ORDINALITY,
       ch VARCHAR(64) PATH '$' NULL ON ERROR
     )) c
WHERE a.asset_type = 'agents' AND a.config_json IS NOT NULL AND JSON_VALID(a.config_json)
  AND c.ch IS NOT NULL AND c.ch <> '';
