-- 生产冻结产物：正文等文本类输入在首次执行时落库，重试复用同一份字节。
-- 只插入不更新：同一任务同一 kind 只有一份；要换内容必须换任务（新上下文）。
-- 图片/二进制资产不放这里（longtext 不是二进制容器），等对象存储方案一起做。
-- 幂等：首次建表裸写 CREATE TABLE；重复执行报 1050，属预期。
CREATE TABLE heron_production_artifact (
  artifact_id bigint NOT NULL,
  task_id bigint NOT NULL,
  kind varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'article / dataset / ...',
  source_url varchar(2048) NULL,
  content_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化内容的 sha256',
  content longtext NOT NULL,
  content_bytes int NOT NULL,
  fetched_at datetime(3) NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (artifact_id),
  UNIQUE KEY uk_artifact_task_kind (task_id, kind),
  KEY ix_artifact_sha (content_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
