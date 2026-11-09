ALTER TABLE memory_item
  ADD COLUMN knowledge_base_ids JSON NULL;

-- fact 层的知识库作用域标记：写入时记录证据来源的知识库 ID 数组（JSON 数字数组，如 [12,13]）。
-- 召回圈定语义：会话绑定了知识库时，只召回 (无标记 = 全局) 或 (标记与会话圈定有交集) 的 fact；
-- 未圈定的会话维持现状（可见全部）。
