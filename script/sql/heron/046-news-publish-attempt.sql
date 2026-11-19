-- 发布尝试独立于发布事实：上传前提交；未知结果保留锁，人工核对后才能释放。
-- 迁移仅生成，应用代码启用前须由部署流程执行。回滚前必须先停用新发布入口并处置未决尝试。
CREATE TABLE heron_news_publish_attempt (
  attempt_id bigint NOT NULL,
  candidate_id bigint NOT NULL,
  work_id bigint NOT NULL,
  version_id bigint NOT NULL,
  video_sha char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source varchar(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  account varchar(120) NULL,
  status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  started_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  actor_id bigint NOT NULL,
  failure_code varchar(64) NULL,
  resolved_by bigint NULL,
  resolved_at datetime(3) NULL,
  resolution_reason varchar(1000) NULL,
  active_candidate_id bigint GENERATED ALWAYS AS (
    CASE WHEN status IN ('started','unknown','submitted','published') THEN candidate_id ELSE NULL END
  ) STORED,
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uk_news_publish_active_candidate (active_candidate_id),
  KEY ix_news_publish_version (version_id,attempt_id),
  CONSTRAINT ck_news_publish_status CHECK (status IN ('started','unknown','submitted','published','not_published'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布副作用账本；仅人工确认未发出后允许下一次尝试';
