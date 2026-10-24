-- 工作流人工审批节点（V36）：agent_tool_confirmation 关联工作流任务/步骤。
-- 审批节点复用确认任务表（无工具执行语义，tool_asset_id 为空），审批结果经事件回调图运行时。
ALTER TABLE agent_tool_confirmation
    ADD COLUMN task_id VARCHAR(64) NULL AFTER chat_id,
    ADD COLUMN step_id VARCHAR(64) NULL AFTER task_id;
