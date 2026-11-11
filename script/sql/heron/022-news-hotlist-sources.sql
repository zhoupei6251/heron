-- 热榜采集源：微博热搜与百度热搜。抖音热榜接口需签名，返回空体，暂不接。
-- source_type 取 weibo / baidu，由 NewsSourceFetcher 的对应解析器处理。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_news_source
(source_id,name,source_type,url,interval_seconds,enabled,auto,
 last_attempt_at,last_success_at,next_fetch_at,fetch_token,fetch_lease_until,
 failure_count,error_code,error_reason,create_dept,create_by,create_time,update_by,update_time)
VALUES
(910001,'微博热搜','weibo','https://weibo.com/ajax/side/hotSearch',600,'0','0',
 NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3)),
(910002,'百度热搜','baidu','https://top.baidu.com/board?tab=realtime',600,'0','0',
 NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3));
