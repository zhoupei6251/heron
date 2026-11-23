-- geo 形态源：USGS 地震分布（地点/经纬度/震级），供数据看板线 3D 地球模板。
-- 与 usgs-earthquakes（按日计数，wide）同源不同口径：那条讲次数趋势，这条讲空间分布。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('usgs-earthquakes-geo','USGS·全球地震分布','news',
 'https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&minmagnitude=4.5',
 NULL,'GET','json','daily',30,'true','https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits',
 'geo','0','ok',
 '公共领域；近 30 天 4.5 级以上地震地点与震级，进 3D 地球模板，免 key',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));