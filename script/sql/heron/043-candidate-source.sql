-- 候选来源：research 表示由内容决策提升而来，空表示热榜抓取。
-- 为什么独立成列而不是从 evidence JSON 里现解析：
-- 自动模式的来源准入、效果归因的来源维度都要判「这条候选是谁给的」。
-- 靠 evidence 的结构推，形状一变就静默失效——它不会报错，只会把研究候选算成热榜。
-- 存量行全部是热榜来源，留空即可，不需要回填。
-- 回滚：ALTER TABLE heron_news_candidate DROP COLUMN source_kind;
ALTER TABLE heron_news_candidate
  ADD COLUMN source_kind varchar(16) NULL COMMENT '来源：research / 空=热榜' AFTER evidence;
