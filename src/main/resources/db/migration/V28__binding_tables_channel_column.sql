-- 修复 V25 缺陷：AgentBindingMapper.insertBatch 是六表统一的 INSERT（含 channel 列），
-- 但 channel 列只建在 agent_publish_channel 上，其余五张绑定表缺失——
-- 导致"创建任何带绑定（modelId/tools 等）的智能体"在真实 MySQL 上必然
-- SQLSyntaxErrorException: Unknown column 'channel'。V25 设计注释本意即
-- "六张绑定表结构统一"，此处补齐（nullable，非渠道表不使用）。
-- 本地未暴露原因：冒烟用例 disabledWithoutDocker 仅 CI 执行，且此前生产
-- 从未创建过带绑定的智能体（模型服务晚于 V25 就绪）。

ALTER TABLE agent_model_binding     ADD COLUMN channel VARCHAR(64) NULL AFTER execution_confirm;
ALTER TABLE agent_workflow_binding  ADD COLUMN channel VARCHAR(64) NULL AFTER execution_confirm;
ALTER TABLE agent_tool_binding      ADD COLUMN channel VARCHAR(64) NULL AFTER execution_confirm;
ALTER TABLE agent_knowledge_binding ADD COLUMN channel VARCHAR(64) NULL AFTER execution_confirm;
ALTER TABLE agent_safety_binding    ADD COLUMN channel VARCHAR(64) NULL AFTER execution_confirm;
