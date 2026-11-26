-- 视频导演系统 S1c：拆除模板中心遗留的数据库对象。
-- 模板注册表不存在（TemplateCatalogService 只扫 data/templates 目录，无 CREATE TABLE），
-- 唯一被删实体的表是 heron_scene_rework（V64 建表，heron 库不用外键，无约束需要先拆）；
-- 模板目录菜单随 TemplateController 一并悬空。
--
-- 执行前提（必须逐条确认，否则本迁移会不可逆地丢数据）：
--   1. SceneReworkService 已下线：场景级返工链路不再写 heron_scene_rework
--   2. 已备份 heron_scene_rework（返工审计指纹，删后不可恢复）
--
-- 回滚：heron_scene_rework 只能从备份还原；菜单行可按 004-menu.sql 第 66-67 行重播。

-- 1. 丢场景级返工审计表
DROP TABLE IF EXISTS heron_scene_rework;

-- 2. 模板目录菜单与角色授权（004-menu.sql 的固定保留段 2080-2081）
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000002080 AND 1761400000000002081;
DELETE FROM sys_menu      WHERE menu_id BETWEEN 1761400000000002080 AND 1761400000000002081;
