-- 热榜种子源：微博热搜、百度热搜。
--
-- 取值沿用 script/sql/heron/022-news-hotlist-sources.sql：固定 source_id 910001/910002，
-- enabled='0'（启用）、auto='0'（允许自动产出）、间隔 600 秒、next_fetch_at 为空。
-- next_fetch_at 为空不会挡住采集：HeronNewsSourceMapper#dueSourceIds 把 NULL 视为到期。
--
-- 幂等：按 source_id 或 source_type 判重，手工建过的存量库直接跳过，
-- 不依赖主键冲突兜底，也不会覆盖运维改过的行。
--
-- 回滚：
--   DELETE FROM heron_news_source
--   WHERE source_id IN (910001, 910002) AND source_type IN ('weibo', 'baidu');

INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 910001,'微博热搜','weibo','https://weibo.com/ajax/side/hotSearch',600,'0','0',
       NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM heron_news_source WHERE source_id = 910001 OR source_type = 'weibo'
);

INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 910002,'百度热搜','baidu','https://top.baidu.com/board?tab=realtime',600,'0','0',
       NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM heron_news_source WHERE source_id = 910002 OR source_type = 'baidu'
);
