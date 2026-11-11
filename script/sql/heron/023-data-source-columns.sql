-- 五栏内容源：市值榜、半导体 ETF、中证 TMT、CS 新能车。
-- 市值榜按市场汇率折人民币；行业指数只作趋势/科技代理，不声称是销量。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('eastmoney-marketcap','东方财富·中美科技市值榜','finance',
 'https://push2.eastmoney.com/api/qt/ulist.np/get?fields=f12,f13,f14,f20',
 NULL,'GET','json','daily',NULL,'unknown',NULL,
 'bars','0','ok',
 '20 家公司；美股/港股按当日汇率折人民币，单位亿元',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('eastmoney-semi','东方财富·半导体ETF','finance',
 'https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.512480',
 NULL,'GET','json','daily',NULL,'unknown',NULL,
 'line','0','ok',
 '科技栏目；ETF 走势，不是芯片销量',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('eastmoney-tmt','东方财富·中证TMT','finance',
 'https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.000998',
 NULL,'GET','json','daily',NULL,'unknown',NULL,
 'line','0','ok',
 '科技栏目；TMT 指数走势',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('eastmoney-nev','东方财富·CS新能车','finance',
 'https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=0.399976',
 NULL,'GET','json','daily',NULL,'unknown',NULL,
 'line','0','ok',
 '趋势栏目；指数走势，不是新能源车销量',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
