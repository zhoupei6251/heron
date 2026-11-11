-- OWID 静态长序列：不做日更，按周更/专题使用；年度日期写成 YYYY-01-01。
-- 与 OwidSeriesAdapter.ALL 对应。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('owid-life-expectancy-world','OWID·全球预期寿命','macro',
 'https://ourworldindata.org/grapher/life-expectancy.csv',
 NULL,'GET','csv','annual',NULL,'true','https://ourworldindata.org/faqs',
 'line','0','ok',
 '静态长序列；世界实体；CC BY 4.0 需署名',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('owid-population-world','OWID·全球人口','macro',
 'https://ourworldindata.org/grapher/population.csv',
 NULL,'GET','csv','annual',NULL,'true','https://ourworldindata.org/faqs',
 'line','0','ok',
 '静态长序列；单位十亿人；CC BY 4.0 需署名',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('owid-co2-world','OWID·全球二氧化碳排放','macro',
 'https://ourworldindata.org/grapher/annual-co2-emissions-per-country.csv',
 NULL,'GET','csv','annual',NULL,'true','https://ourworldindata.org/faqs',
 'line','0','ok',
 '静态长序列；单位百万吨；CC BY 4.0 需署名',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
