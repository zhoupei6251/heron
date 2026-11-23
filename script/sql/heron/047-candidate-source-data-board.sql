-- 数据看板占位候选的来源归因回填。
-- 缺陷：placeholderCandidate 一直没写 source_kind，而列语义是「空=热榜」，
-- 效果归因的来源维度会把数据看板作品错算成热榜（设计文档 §11.6 点名必修）。
-- 写侧已修（占位候选写 data_board）；这里只回填存量占位行。
-- 占位行的识别特征是代码里写死的 title 前缀 + summary，二者同时匹配才更新。
-- 回滚：UPDATE heron_news_candidate SET source_kind = NULL
--       WHERE source_kind = 'data_board' AND title LIKE '数据看板 · %';
UPDATE heron_news_candidate
SET source_kind = 'data_board'
WHERE source_kind IS NULL
  AND title LIKE '数据看板 · %'
  AND summary = '数据看板线由数据集驱动，非新闻候选';