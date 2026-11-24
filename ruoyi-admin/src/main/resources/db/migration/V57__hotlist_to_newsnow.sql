-- 热榜切到 NewsNow：TrendRadar 那 11 个平台一次接齐，停掉已经 403 的微博直连。
--
-- 为什么换：
--   1. 微博直连 https://weibo.com/ajax/side/hotSearch 从 2026-11-24 起稳定返回 403，
--      直连就是赌对方不封，换一家封一家；NewsNow 替我们扛反爬，实例挂了改一行 base URL。
--   2. NewsNow 是 MIT、免费、可自托管；适配层（NewsNowCatalog 43 平台 + parseNewsNow）早就写好了，
--      一直没启用，等于现成的能力没用上。
--
-- 平台清单取 TrendRadar 的默认 11 家（去重后 13 个 NewsNow id）：知乎、抖音、B站热搜、华尔街见闻×2、
-- 贴吧、百度、财联社×2、澎湃、凤凰、头条、微博。实测全部可用。
--
-- 幂等：按 source_type='newsnow' + url 判重，手工建过的跳过。
-- 微博直连用 source_type='weibo' + 原 URL 精确匹配，只动这一行。
--
-- 回滚：
--   DELETE FROM heron_news_source WHERE source_type = 'newsnow' AND fetch_token IS NULL
--     AND source_id BETWEEN 920001 AND 920013;
--   UPDATE heron_news_source SET enabled='0' WHERE source_id = 910001;

-- 1) 微博直连停用：它已经 403，留着只会一直累加 failure_count。
UPDATE heron_news_source
SET enabled = '1', next_fetch_at = NULL, update_time = UTC_TIMESTAMP(3)
WHERE source_id = 910001
  AND source_type = 'weibo'
  AND url = 'https://weibo.com/ajax/side/hotSearch';

-- 2) NewsNow 13 个平台。source_id 固定区间，方便回滚时精确删掉这一批。
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920001,'知乎','newsnow','https://newsnow.busiyi.world/api/s?id=zhihu',600,'0','0',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920001 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=zhihu'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920002,'抖音','newsnow','https://newsnow.busiyi.world/api/s?id=douyin',600,'0','0',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920002 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=douyin'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920003,'B站热搜','newsnow','https://newsnow.busiyi.world/api/s?id=bilibili-hot-search',600,'0','0',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920003 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=bilibili-hot-search'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920004,'华尔街见闻热门','newsnow','https://newsnow.busiyi.world/api/s?id=wallstreetcn-hot',900,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920004 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=wallstreetcn-hot'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920005,'华尔街见闻资讯','newsnow','https://newsnow.busiyi.world/api/s?id=wallstreetcn-news',900,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920005 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=wallstreetcn-news'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920006,'贴吧','newsnow','https://newsnow.busiyi.world/api/s?id=tieba',600,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920006 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=tieba'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920007,'百度热搜','newsnow','https://newsnow.busiyi.world/api/s?id=baidu',600,'0','0',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920007 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=baidu'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920008,'财联社热门','newsnow','https://newsnow.busiyi.world/api/s?id=cls-hot',600,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920008 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=cls-hot'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920009,'财联社电报','newsnow','https://newsnow.busiyi.world/api/s?id=cls-telegraph',600,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920009 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=cls-telegraph'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920010,'澎湃新闻','newsnow','https://newsnow.busiyi.world/api/s?id=thepaper',1200,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920010 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=thepaper'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920011,'凤凰网','newsnow','https://newsnow.busiyi.world/api/s?id=ifeng',1200,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920011 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=ifeng'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920012,'今日头条','newsnow','https://newsnow.busiyi.world/api/s?id=toutiao',600,'0','1',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920012 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=toutiao'));
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
SELECT 920013,'微博热搜','newsnow','https://newsnow.busiyi.world/api/s?id=weibo',600,'0','0',NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM heron_news_source WHERE source_id = 920013 OR (source_type='newsnow' AND url='https://newsnow.busiyi.world/api/s?id=weibo'));
