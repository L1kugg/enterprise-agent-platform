-- R2.2 观测看板：智能体调用日志补 token 用量两列（planner/成稿等本智能体 LLM 调用的
-- 估算值，由 AgentChatRuntime 在 RunState 累计后随调用日志写入；NULL = 该版本未记录）。
ALTER TABLE agent_invocation_log
  ADD COLUMN input_tokens BIGINT NULL AFTER latency_ms,
  ADD COLUMN output_tokens BIGINT NULL AFTER input_tokens;
