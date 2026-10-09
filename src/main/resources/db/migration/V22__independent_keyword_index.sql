-- Independent lexical recall lane. MySQL FULLTEXT is intentionally separate from
-- pgvector so a vector-store outage cannot remove exact-match candidates.
CREATE TABLE IF NOT EXISTS retrieval_keyword_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  chunk_key VARCHAR(128) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  chat_id VARCHAR(128) NULL,
  job_id VARCHAR(64) NOT NULL,
  chunk_index INT NOT NULL,
  title VARCHAR(512) NOT NULL,
  content LONGTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  indexed_at DATETIME NOT NULL,
  UNIQUE KEY uk_keyword_chunk (tenant_id, job_id, chunk_index),
  INDEX idx_keyword_chunk_tenant_chat (tenant_id, chat_id),
  FULLTEXT KEY ft_keyword_chunk_title_content (title, content) WITH PARSER ngram
);
