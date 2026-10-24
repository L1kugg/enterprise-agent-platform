-- 点踩原因（业务效果看板，V35）：answer_feedback 加 reason 列。
-- 前端点踩时从固定原因集快选（不准确/答非所问/内容过时/没引用来源/其他），
-- 服务端白名单校验后落库；历史行 NULL = 未采集原因，聚合时排除。
ALTER TABLE answer_feedback
    ADD COLUMN reason VARCHAR(64) NULL AFTER comment;
