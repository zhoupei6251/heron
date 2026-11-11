-- 新增两个日更源，并把年度口径的来源明确标成「不进日更路由」。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('usgs-earthquakes','USGS·全球地震目录','news',
 'https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&minmagnitude=4.5',
 NULL,'GET','json','daily',30,'true','https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits',
 'line','0','ok',
 '公共领域；按日统计 4.5 级以上次数，免 key',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('wikimedia-zh-top','维基百科·中文热读榜','platform',
 'https://wikimedia.org/api/rest_v1/metrics/pageviews/top/zh.wikipedia/all-access',
 NULL,'GET','json','daily',1,'true','https://wikimedia.org/api/rest_v1/',
 'bars','0','ok',
 'CC0；取 UTC 昨日排行，进 bars 模板',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('gapminder','Gapminder·国家历史数据','macro',
 'https://www.gapminder.org/data/',
 NULL,'GET','csv','annual',NULL,'unknown','https://www.gapminder.org/terms-of-use/',
 'line','1','ok',
 '静态数据集，需人工下载；年度口径，不进日更路由',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));

-- 蒸馏结论：年度/季度的宏观平台只做专题，不参与日更路由。
UPDATE heron_data_source
SET probe_note='年度口径，仅供专题，不进日更路由', update_time=UTC_TIMESTAMP(3)
WHERE source_key IN ('worldbank','owid','dbnomics');
