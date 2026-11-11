-- 两个独立源族：Hacker News 与 GitHub，不与现有任何上游共享数据。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('hackernews-top','Hacker News·热帖榜','platform',
 'https://hacker-news.firebaseio.com/v0/topstories.json',
 NULL,'GET','json','daily',1,'unknown','https://news.ycombinator.com/legal',
 'bars','0','ok',
 '免 key；榜单 1 次 + 每条详情各 1 次请求；rows 形态',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('github-trending','GitHub·新晋热门仓库','platform',
 'https://api.github.com/search/repositories',
 NULL,'GET','json','daily',30,'unknown','https://docs.github.com/en/site-policies',
 'bars','0','ok',
 '免 key，搜索接口限流 10 次/分钟；rows 形态',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
