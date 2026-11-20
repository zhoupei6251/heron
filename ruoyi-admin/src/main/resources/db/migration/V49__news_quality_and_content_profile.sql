-- 内容质量报告与内容属性：L3 审稿结果落版本行，不新建表；两列都可为 NULL，缺失不阻塞任何流程。
--
-- quality_report：成片级质量报告 JSON（quality-review/1）。它只供人参考，不参与
-- checks 结构、不改任何闸、不进自动发布门禁——自动发布仍然只认 editorial_review 的 PASS。
--
-- content_profile：生产期才知道的两项内容属性（标题钩子类型、开头结构）。
-- 其余归因属性都能从冻结快照或现有表直接取到，不再另存一份。
--
-- strategy_override：人工对编辑策略的显式覆盖（lock / veto / override），只追加不改写原决策。
-- 没有期限的锁会永久改变策略，所以 UI/服务层必须要求 scopeUntil。
--
-- 回滚：先确认 UI 与归因接口已下线对应字段，再
--   ALTER TABLE heron_video_version DROP COLUMN quality_report, DROP COLUMN content_profile;
--   ALTER TABLE heron_auto_slot DROP COLUMN strategy_override;

ALTER TABLE heron_video_version
    ADD COLUMN quality_report TEXT NULL COMMENT '成片级质量审稿报告 JSON：class/confidence/evidence 三类结论，不做质量总分',
    ADD COLUMN content_profile JSON NULL COMMENT '内容属性补充：hookType 与 opening 结构；取不到时整列或字段缺失，归因按未记录';

ALTER TABLE heron_auto_slot
    ADD COLUMN strategy_override JSON NULL COMMENT '人工策略覆盖记录：action/scope/value/reason/decidedBy/scopeUntil，只追加';
