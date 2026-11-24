-- 主键统一改名为 id：外键保留 xxx_id，只有「当前表自己的主键」改。
--
-- 规范由用户 2026-11-24 定：当前表主键 id，关联其他表 表名_id。
-- 之前每张表的主键都写成了业务名（work_id / version_id / task_id …），
-- 同一个概念换一张表就换个名字，SQL 与 Java 层要反复对照。
--
-- 只改主键列，不动外键：heron_task_step.task_id、heron_video_version.work_id
-- 这些指向别人的列本来就叫对了，改它们反而会让关联关系读不出来。
--
-- 两条天然主键表不在本次范围，它们的键本身有业务含义，不是自造 ID：
--   heron_production_lane (biz_type)
--   heron_data_source     (source_key)
--   heron_auto_slot       (slot_date, slot_time) 复合
--   heron_agent_state     (session_id, state_key, item_index) 复合
--
-- 全库没有 AUTO_INCREMENT，主键都由 IdWorker 赋值，重命名不涉及自增语义。
-- MySQL 8.0 RENAME COLUMN 会连带改掉依赖该列的索引与约束定义。
--
-- 回滚：把每条的 RENAME COLUMN id TO xxx_id 反向执行即可。

ALTER TABLE heron_agent_tool_call      RENAME COLUMN call_id       TO id;
ALTER TABLE heron_content_decision     RENAME COLUMN decision_id   TO id;
ALTER TABLE heron_data_opportunity     RENAME COLUMN opportunity_id TO id;
ALTER TABLE heron_dataset_usage        RENAME COLUMN usage_id      TO id;
ALTER TABLE heron_jev_shadow           RENAME COLUMN shadow_id     TO id;
ALTER TABLE heron_news_candidate       RENAME COLUMN candidate_id  TO id;
ALTER TABLE heron_news_metric          RENAME COLUMN metric_id     TO id;
ALTER TABLE heron_news_publish_attempt RENAME COLUMN attempt_id    TO id;
ALTER TABLE heron_news_source          RENAME COLUMN source_id     TO id;
ALTER TABLE heron_news_work            RENAME COLUMN work_id       TO id;
ALTER TABLE heron_production_artifact  RENAME COLUMN artifact_id   TO id;
ALTER TABLE heron_production_context   RENAME COLUMN context_id    TO id;
ALTER TABLE heron_production_task      RENAME COLUMN task_id       TO id;
ALTER TABLE heron_proto_project        RENAME COLUMN project_id    TO id;
ALTER TABLE heron_proto_version        RENAME COLUMN version_id    TO id;
ALTER TABLE heron_research_snapshot    RENAME COLUMN snapshot_id   TO id;
ALTER TABLE heron_research_task        RENAME COLUMN research_id   TO id;
ALTER TABLE heron_strategy_hypothesis  RENAME COLUMN hypothesis_id TO id;
ALTER TABLE heron_task_step            RENAME COLUMN step_id       TO id;
ALTER TABLE heron_trend_video          RENAME COLUMN trend_id      TO id;
ALTER TABLE heron_usage_record         RENAME COLUMN usage_id      TO id;
ALTER TABLE heron_video_version        RENAME COLUMN version_id    TO id;
