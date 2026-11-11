-- OWID 国家赛跑源：long 形态，多实体 × 多时点。
-- 只保留带 ISO3 码的实体，窗口 1950-2023，历史峰值前 20，年份等距 30 个时点。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('owid-population-race','OWID·各国人口赛跑','macro',
 'https://ourworldindata.org/grapher/population.csv',
 NULL,'GET','csv','annual',NULL,'true','https://ourworldindata.org/faqs',
 'race','0','ok',
 'long 形态；ISO3 实体、1950-2023、峰值前 20、30 个时点',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('owid-life-expectancy-race','OWID·各国预期寿命赛跑','macro',
 'https://ourworldindata.org/grapher/life-expectancy.csv',
 NULL,'GET','csv','annual',NULL,'true','https://ourworldindata.org/faqs',
 'race','0','ok',
 'long 形态；峰值集中在微型国家，选题慎用',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('owid-co2-race','OWID·各国碳排放赛跑','macro',
 'https://ourworldindata.org/grapher/annual-co2-emissions-per-country.csv',
 NULL,'GET','csv','annual',NULL,'true','https://ourworldindata.org/faqs',
 'race','0','ok',
 'long 形态；ISO3 实体、1950-2023、峰值前 20、30 个时点',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
