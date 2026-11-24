-- 参考素材表主键改名：reference_asset_id → id，与 V61 里其余 22 张表对齐。
--
-- 为什么单独一条而不是并进 V61：V61 已经应用过了（flyway 记在 v61），
-- 改已应用的脚本会校验和不匹配、拒绝启动。只能往前补一条。
--
-- 回滚：ALTER TABLE heron_reference_asset RENAME COLUMN id TO reference_asset_id;

ALTER TABLE heron_reference_asset RENAME COLUMN reference_asset_id TO id;
