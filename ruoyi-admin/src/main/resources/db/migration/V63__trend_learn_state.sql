-- 爆款学习闭环只扩展现有热点视频行，不新建任务表或配方表。
-- LEARNED 由应用条件更新保证不可逆；数据库默认值让历史采集行从 SEEN 开始。
-- 回滚：ALTER TABLE heron_trend_video DROP INDEX ix_trend_learn_state, DROP COLUMN oss_id, DROP COLUMN learn_note, DROP COLUMN learn_state;

ALTER TABLE heron_trend_video
  ADD COLUMN learn_state varchar(24) NOT NULL DEFAULT 'SEEN' COMMENT '学习状态：SEEN/SELECTED/DOWNLOADED/DECOMPOSED/LEARNED/*_FAILED' AFTER create_time,
  ADD COLUMN learn_note varchar(500) DEFAULT NULL COMMENT '学习尝试次数、租约令牌、失败原因或产物位置' AFTER learn_state,
  ADD COLUMN oss_id bigint DEFAULT NULL COMMENT '原片在 sys_oss 中的主键' AFTER learn_note,
  ADD KEY ix_trend_learn_state (learn_state, duration_ms);
