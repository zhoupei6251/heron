-- 原型线作废：拆视频四线的 biz_type，清原型任务与两张表
--
-- 执行前提（必须逐条确认，否则本迁移会不可逆地丢数据）：
--   1. worker 已停：heron.production.worker-enabled=false，且确认没有在途任务
--   2. 已备份：heron_proto_project / heron_proto_version / heron_production_task /
--      heron_task_step / heron_usage_record / heron_production_context /
--      heron_production_artifact
--   3. 已确认原型线的历史任务与项目不再需要
--
-- 本迁移含不可逆的 DROP TABLE 与 DELETE，Flyway 社区版不自动回滚。

-- 1. 加宽：knowledge_explainer 19 字符，原 varchar(16) 装不下
ALTER TABLE heron_production_lane MODIFY biz_type varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE heron_production_task MODIFY biz_type varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

-- 2. 回填：存量 news_video 任务按冻结快照的 kind 拆到四条线。
--    取不到 kind 的按解说线，与 kindOf(null) 的现有兜底一致。
UPDATE heron_production_task
SET biz_type = CASE JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.kind'))
    WHEN 'data_board'          THEN 'data_board'
    WHEN 'trend'               THEN 'trend_insight'
    WHEN 'knowledge_explainer' THEN 'knowledge_explainer'
    ELSE 'news_explainer'
  END
WHERE biz_type = 'news_video';

-- 3. 清原型任务：先子表后主表，否则主表删了子表就成孤儿
DELETE FROM heron_task_step
 WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_usage_record
 WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_production_context
 WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_production_artifact
 WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_production_task WHERE biz_type = 'prototype';

-- 4. 丢原型表
DROP TABLE IF EXISTS heron_proto_version;
DROP TABLE IF EXISTS heron_proto_project;

-- 5. 容量锁行：五条线各一行，用 upsert 而不是先清表重播，
--    避免在库已经手工执行过本迁移时把不在清单里的行一并抹掉。
INSERT INTO heron_production_lane (biz_type, create_time, update_time) VALUES
  ('news_explainer',      UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('data_board',          UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('trend_insight',       UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('knowledge_explainer', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('music_video',         UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE update_time = VALUES(update_time);

-- 原型那条锁行单独删：它在 001-production.sql 里作为基线数据存在。
DELETE FROM heron_production_lane WHERE biz_type = 'prototype';

-- 6. 原型菜单与角色授权（004-menu.sql 的固定保留段 2100-2110）
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000002100 AND 1761400000000002110;
DELETE FROM sys_menu      WHERE menu_id BETWEEN 1761400000000002100 AND 1761400000000002110;

-- 回滚：第 4 步 DROP 的两张表与第 2、3 步删除的行均不可恢复，只能从备份还原。
-- 第 1 步可用 ALTER TABLE ... MODIFY biz_type varchar(16) 回退（需先确保没有超长值）。
