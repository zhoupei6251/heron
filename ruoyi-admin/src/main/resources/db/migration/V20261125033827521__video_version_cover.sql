-- 成片封面只存受保护预览地址；图片字节仍在生产产物清单与对象存储中。
-- 回滚：ALTER TABLE heron_video_version DROP COLUMN cover_url;

ALTER TABLE heron_video_version
    ADD COLUMN cover_url varchar(2048) NULL COMMENT '原比例封面预览地址' AFTER artifact_manifest;
