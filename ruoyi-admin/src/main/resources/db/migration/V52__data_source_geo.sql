-- geo 形态源：USGS 地震分布（地点/经纬度/震级），供数据看板线 3D 地球模板。
-- 取值沿用 script/sql/heron/046-data-source-geo.sql；enabled='0'（启用）。
-- geo 快照由 UsgsQuakeGeoAdapter 产出；没有 3D 模板时选模板层会拒绝候选，不会退回 2D。
--
-- 幂等：按 source_key 判重，手工建过的存量库直接跳过，不覆盖运维改过的行。
--
-- 回滚：
--   DELETE FROM heron_data_source WHERE source_key = 'usgs-earthquakes-geo';

INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
SELECT 'usgs-earthquakes-geo','USGS·全球地震分布','news',
       'https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&minmagnitude=4.5',
       NULL,'GET','json','daily',30,'true','https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits',
       'geo','0','ok',
       '公共领域；近 30 天 4.5 级以上地震地点与震级，进 3D 地球模板，免 key',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM heron_data_source WHERE source_key = 'usgs-earthquakes-geo'
);