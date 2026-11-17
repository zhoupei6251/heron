-- 候选所属的研究，从证据 JSON 落成列。
--
-- 为什么要有这一列：现在「某条研究产出了哪些候选」只能解析 evidence.items[0].researchId
-- 反查。处理「同一研究重跑堆出重复候选」那类问题时，这个关系必须能直接查，
-- 靠 JSON 解析既慢又会在证据结构变化时静默失效。
--
-- 回填只针对 source_kind='research' 的行；热榜候选没有研究，留空。
-- 回滚：
--   ALTER TABLE heron_news_candidate DROP COLUMN research_id;

ALTER TABLE heron_news_candidate
  ADD COLUMN research_id bigint NULL COMMENT '研究候选所属的研究 id；热榜来源为空' AFTER source_key;

UPDATE heron_news_candidate
SET research_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(evidence, '$.items[0].researchId')) AS UNSIGNED)
WHERE source_kind = 'research'
  AND research_id IS NULL
  AND JSON_EXTRACT(evidence, '$.items[0].researchId') IS NOT NULL;
