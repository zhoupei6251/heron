-- 数据看板去重账本：一条数据看板成功入库后记一行，路由按 7 天窗口排除。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
CREATE TABLE heron_dataset_usage (
  usage_id bigint NOT NULL,
  source_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  entity varchar(64) NOT NULL,
  metric varchar(40) NULL,
  version_id bigint NOT NULL,
  used_at datetime(3) NOT NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (usage_id),
  UNIQUE KEY uk_dataset_usage_version (version_id),
  KEY ix_dataset_usage_recent (used_at,source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
