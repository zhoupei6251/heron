-- 预期寿命赛跑下架：按历史峰值取前 20 全是摩纳哥、圣马力诺这类微型地区，讲不出选题。
-- 适配器侧同步从 OwidRaceAdapter.ALL 摘除；保留行是为了让 usage/审计还能查到这条源的历史。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
UPDATE heron_data_source
SET enabled = '1',
    probe_note = '已下架：峰值前 20 全是微型国家，选题价值低',
    update_time = UTC_TIMESTAMP(3)
WHERE source_key = 'owid-life-expectancy-race';
