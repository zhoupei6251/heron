-- 数据看板占位候选的来源归因回填（对应 script/sql/heron/047）。
-- 写侧已修：placeholderCandidate 写 source_kind='data_board'；这里只补存量。
-- UPDATE 带 source_kind IS NULL 条件，重复执行无副作用。
UPDATE heron_news_candidate
SET source_kind = 'data_board'
WHERE source_kind IS NULL
  AND title LIKE '数据看板 · %'
  AND summary = '数据看板线由数据集驱动，非新闻候选';