-- 热榜只留 NewsNow + RSS：删掉微博直连与百度直连两条来源。
--
-- 为什么删：
--   微博直连 https://weibo.com/ajax/side/hotSearch 从 2026-11-24 起稳定 403；
--   百度直连要解析 HTML 里的 s-data，对方一改版就断，同样是赌。
--   两家在 NewsNow 里都有（920013 微博 / 920007 百度），且由 NewsNow 统一扛反爬，
--   所以直连没有存在的意义，留着只会一直累加 failure_count。
--
-- 同时删掉对应的解析器分支（parseWeibo / parseBaidu），历史采集到的候选不受影响：
-- 候选行里存的是 evidence 快照，不依赖来源行是否还在。
--
-- 回滚：需要恢复时重跑 V50 的 weibo/baidu 两段 INSERT（source_id 910001/910002）。

DELETE FROM heron_news_source
WHERE (source_id = 910001 AND source_type = 'weibo' AND url = 'https://weibo.com/ajax/side/hotSearch')
   OR (source_id = 910002 AND source_type = 'baidu' AND url LIKE 'https://top.baidu.com/board%');
