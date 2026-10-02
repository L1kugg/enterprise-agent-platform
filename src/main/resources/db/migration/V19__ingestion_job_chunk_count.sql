-- Record the vector chunk count on the ingestion job once processing succeeds.
-- 知识库「文档清单」展示切片数用：入库成功时由 IngestionService 回写，
-- 失败/重试中为 NULL；纯展示字段，不参与任何检索逻辑。
ALTER TABLE ingestion_job
  ADD COLUMN chunk_count INT NULL AFTER max_retries;
