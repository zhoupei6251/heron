-- 候选实体与提升服务已经读写research_id；补齐初始化迁移中的缺列。
-- 回滚：ALTER TABLE heron_news_candidate DROP COLUMN research_id;
ALTER TABLE heron_news_candidate
  ADD COLUMN research_id bigint NULL COMMENT '研究候选所属研究；热榜来源为空' AFTER source_key,
  ADD KEY ix_news_candidate_research (research_id);
