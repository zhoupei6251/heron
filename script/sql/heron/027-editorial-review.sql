-- AI 审稿结论必须落盘，否则自动发布无法证明「审过」这件事发生过。
-- 幂等：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行报 1060，属预期。
ALTER TABLE heron_video_version
    ADD COLUMN editorial_review TEXT NULL COMMENT 'AI 审稿结论 JSON：result / issues / reviewedAt / promptVersion / repairAttempts';
