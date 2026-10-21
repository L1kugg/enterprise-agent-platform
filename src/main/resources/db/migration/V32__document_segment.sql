-- R8 知识库分段治理：真实分段落库（此前切片只存在于向量库/关键词索引，
-- 无法查看/编辑/重建）。入库成功时由 IngestionService 按 job 落一份分段快照，
-- 分段编辑/拆分/合并后据此重嵌入向量。存量文档无分段数据，
-- 可经"重建索引"（重解析磁盘原文件）补齐。
CREATE TABLE IF NOT EXISTS document_segment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  chat_id VARCHAR(128) NOT NULL,
  job_id VARCHAR(64) NOT NULL,
  seg_index INT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  vector_synced TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_document_segment (tenant_id, job_id, seg_index),
  INDEX idx_document_segment_chat (tenant_id, chat_id, seg_index)
);
