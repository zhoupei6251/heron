-- 编辑运行结构化落库：槽位原本只记一段 detail 文本，编辑策划页无法回放决策链。
-- 就地扩列，不新建表——heron_auto_slot 已经在记 status/detail/work_id，主键是 (slot_date, slot_time)。
-- 幂等：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 1060，属预期。

ALTER TABLE heron_auto_slot
    ADD COLUMN selected_line VARCHAR(32) NULL COMMENT '总编辑选定的线：DATA_BOARD / NEWS_EXPLAINER / NO_PUBLISH',
    ADD COLUMN editorial_intent VARCHAR(500) NULL COMMENT '总编辑给出的选题意图，进入生产输入',
    ADD COLUMN decision_reason VARCHAR(500) NULL COMMENT '为什么选这条线，或为什么今天不发',
    ADD COLUMN candidate_id BIGINT NULL COMMENT '这一槽认定的候选；总编辑之后由线内编辑重新选定时以线内结果为准',
    ADD COLUMN pool_json TEXT NULL COMMENT '决策当时两条线的候选概况快照，用于回放「它看到了什么」';
