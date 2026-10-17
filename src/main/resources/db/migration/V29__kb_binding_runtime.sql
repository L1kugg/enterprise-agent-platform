-- R5 知识库运行时绑定（FR-R5-01/02）：配置态知识库资产与真实文档库打通。
-- ingestion_job.knowledge_base_id：上传时指定目标知识库（platform_asset.id），
--   NULL = 未归属（历史文档与聊天输入框上传保持原行为，租户级共享检索）。
-- retrieval_keyword_chunk.knowledge_base_id：关键词路按知识库过滤的检索列，
--   与向量切片元数据 knowledge_base_id 同源（splitDocuments 写入）。
-- 存量数据不回填：无法可靠判定历史文档归属，回退为"未归属"，仅租户级检索可见。
ALTER TABLE ingestion_job
  ADD COLUMN knowledge_base_id BIGINT NULL AFTER chat_id,
  ADD INDEX idx_ingestion_job_kb (tenant_id, knowledge_base_id);

ALTER TABLE retrieval_keyword_chunk
  ADD COLUMN knowledge_base_id BIGINT NULL AFTER chat_id,
  ADD INDEX idx_keyword_chunk_kb (tenant_id, knowledge_base_id);
