-- 补 ix_news_candidate_research：V46 已把 research_id 落成列，但没建索引。
--
-- 为什么需要：处理「同一研究重跑堆出重复候选」时要按 research_id 反查，
-- 归因查询也走这一列；没有索引就是全表扫 heron_news_candidate。
--
-- 回滚：ALTER TABLE heron_news_candidate DROP INDEX ix_news_candidate_research;

ALTER TABLE heron_news_candidate
  ADD KEY ix_news_candidate_research (research_id);
