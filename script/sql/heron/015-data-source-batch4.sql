-- 周更/专题可用的免 key 源：世行四个国家榜 + Stack Overflow + DEV。
-- 世行为年度口径，按周更或专题使用；7 天去重仍按 source_key 计。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('worldbank-gdp','World Bank·GDP 国家榜','macro',
 'https://api.worldbank.org/v2/country/;CHN;JPN;DEU;IND/indicator/NY.GDP.MKTP.CD',
 NULL,'GET','json','annual',NULL,'true','https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets',
 'bars','0','ok',
 '免 key；mrnev=1 取最近非空年份；单位十亿美元',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('worldbank-population','World Bank·人口国家榜','macro',
 'https://api.worldbank.org/v2/country/;CHN;JPN;DEU;IND/indicator/SP.POP.TOTL',
 NULL,'GET','json','annual',NULL,'true','https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets',
 'bars','0','ok',
 '免 key；单位百万人',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('worldbank-life-expectancy','World Bank·预期寿命榜','macro',
 'https://api.worldbank.org/v2/country/;CHN;JPN;DEU;IND/indicator/SP.DYN.LE00.IN',
 NULL,'GET','json','annual',NULL,'true','https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets',
 'bars','0','ok',
 '免 key；单位岁',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('worldbank-co2-per-capita','World Bank·人均碳排榜','macro',
 'https://api.worldbank.org/v2/country/;CHN;JPN;DEU;IND/indicator/EN.ATM.CO2E.PC',
 NULL,'GET','json','annual',NULL,'true','https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets',
 'bars','0','ok',
 '免 key；单位吨/人',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('stackexchange-hot','Stack Overflow·热问榜','platform',
 'https://api.stackexchange.com/2.3/questions?sort=hot&site=stackoverflow',
 NULL,'GET','json','daily',NULL,'unknown','https://stackexchange.com/legal/api-terms-of-use',
 'bars','0','ok',
 '免 key；每日配额 300 次；rows 形态',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('devto-top','DEV Community·热文榜','platform',
 'https://dev.to/api/articles?top=1',
 NULL,'GET','json','daily',NULL,'unknown','https://dev.to/terms',
 'bars','0','ok',
 '免 key；rows 形态',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
