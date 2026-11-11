-- 静态仓库挖掘：Gapminder 生育率趋势 + vega-datasets 的 IMDb 票房榜。
-- 均为静态数据，按周更/专题使用；7 天去重仍按 source_key 计。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('gapminder-fertility-world','Gapminder·全球生育率趋势','macro',
 'https://raw.githubusercontent.com/vega/vega-datasets/main/data/gapminder.json',
 NULL,'GET','json','annual',NULL,'unknown','https://www.gapminder.org/terms-of-use/',
 'line','0','ok',
 '静态数据；各国未加权平均；需注明 Gapminder',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('vega-movies-gross','IMDb·全球票房榜','entertainment',
 'https://raw.githubusercontent.com/vega/vega-datasets/main/data/movies.json',
 NULL,'GET','json','annual',NULL,'unknown','https://github.com/vega/vega-datasets',
 'bars','0','ok',
 '静态测试数据集；全球票房，单位百万美元',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
