-- 记忆子系统增强（评审结论：fact 生命周期与召回质量三项补强）：
-- 1) fact 记忆补证据源 chat 引用（source_chat_id）：文档删除时按 chatId 级联失效，
--    防"引用已删来源的可信事实"带着高置信度继续进入生成上下文；
-- 2) stale 软失效标记：级联失效不物理删除，memory_event 记 INVALIDATE 保留审计轨迹；
-- 3) embedding_json：写入时缓存的记忆向量（JSON float 数组），召回时与查询向量做
--    词面 + 语义融合精排；列为空（embedding 失败或语义开关关闭）自动回退纯词面。
-- 存量 fact 行的 source 只有 rag:{sourceType}:{title}（无 chatId），无法回填证据源引用，
-- source_chat_id 保持 NULL——这些旧记录删除文档时不级联（保守保留，仅新写入受治理）。
ALTER TABLE memory_item
  ADD COLUMN source_chat_id VARCHAR(64) NULL AFTER source_task_id,
  ADD COLUMN stale TINYINT(1) NOT NULL DEFAULT 0 AFTER source_chat_id,
  ADD COLUMN embedding_json JSON NULL AFTER metadata_json,
  ADD INDEX idx_memory_item_source_chat (source_chat_id);
