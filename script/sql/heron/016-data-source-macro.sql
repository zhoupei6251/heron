-- 东财宏观扩到五个指标：CPI 之外再上 PPI、制造业/非制造业 PMI、GDP 同比。
-- 与 EastmoneyMacroAdapter.ALL 对应。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('eastmoney-ppi','东方财富·PPI 同比','macro',
 'https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_ECONOMY_PPI',
 NULL,'GET','json','monthly',NULL,'unknown',NULL,
 'line','0','ok',
 '月频；字段 BASE_SAME；与适配器目录对应',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('eastmoney-pmi','东方财富·制造业 PMI','macro',
 'https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_ECONOMY_PMI',
 NULL,'GET','json','monthly',NULL,'unknown',NULL,
 'line','0','ok',
 '月频；字段 MAKE_INDEX；与适配器目录对应',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('eastmoney-pmi-non','东方财富·非制造业 PMI','macro',
 'https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_ECONOMY_PMI',
 NULL,'GET','json','monthly',NULL,'unknown',NULL,
 'line','0','ok',
 '月频；字段 NMAKE_INDEX；与 PMI 共用上游',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('eastmoney-gdp','东方财富·GDP 同比','macro',
 'https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_ECONOMY_GDP',
 NULL,'GET','json','monthly',NULL,'unknown',NULL,
 'line','0','ok',
 '季频发布；字段 SUM_SAME；与适配器目录对应',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
