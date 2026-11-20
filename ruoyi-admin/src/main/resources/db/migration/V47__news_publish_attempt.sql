-- 发布尝试账本：上传前先提交一条 started，结果未知就保留锁，人工核对后才能释放。
--
-- 为什么现在补：Java 侧 HeronNewsPublishAttempt / HeronNewsPublishAttemptMapper
-- 已经在读写这张表（含 active_candidate_id 生成列与 FOR UPDATE 查询），但建表语句
-- 只存在于手工迁移时代的 script/sql/heron/046，Flyway locations 下没有它，
-- 部署库启动后这张表根本不存在。
--
-- active_candidate_id 必须是 STORED 生成列 + 唯一键：候选级「只能有一条活动尝试」
-- 这条不变量由数据库兜底，不能靠应用层先查后写。
--
-- 回滚：先停用新发布入口并处置未决尝试，再
--   DROP TABLE heron_news_publish_attempt;

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
