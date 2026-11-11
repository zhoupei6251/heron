-- 发布表现回填（2026-11-09）：把平台侧数据存下来，反哺选题。
-- 一条版本可以在不同时间点多次采样，取最新一条作为当前表现。

CREATE TABLE IF NOT EXISTS heron_news_metric (
  metric_id bigint NOT NULL,
  version_id bigint NOT NULL,
  platform varchar(32) NOT NULL,
  plays bigint NULL,
  completion_rate decimal(5,4) NULL,
  likes bigint NULL,
  comments bigint NULL,
  shares bigint NULL,
  fetched_at datetime(3) NOT NULL,
  create_by bigint NULL,
  create_time datetime(3) NOT NULL,
  PRIMARY KEY (metric_id),
  KEY ix_metric_version (version_id, fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布表现：同一版本多次采样，取最新一条';
