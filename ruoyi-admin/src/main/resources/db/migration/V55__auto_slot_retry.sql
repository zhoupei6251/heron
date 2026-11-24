-- 失败槽位的自动补跑：记已补跑次数，调度器按间隔重试，人不必再去工作台巡检空槽。
--
-- 只有 failed 会被补跑：produced 不该重跑，skipped 是有意的空槽（没素材、没开自动、今天不发）。
-- 额度落库而不是放内存：重启之后补跑次数仍然有效，不会无限重试。
--
-- 回滚：ALTER TABLE heron_auto_slot DROP COLUMN retry_count;

ALTER TABLE heron_auto_slot
  ADD COLUMN retry_count int NOT NULL DEFAULT 0 COMMENT '已补跑次数：0 表示还没补过' AFTER detail;
