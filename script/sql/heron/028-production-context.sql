-- 生产冻结上下文：一次生产一份不可变输入，后续阶段只认这份内容与 hash。
-- 只插入不更新：任何「改内容」都必须新建一行（新版本），旧行保持原样。
-- 幂等：首次建表裸写 CREATE TABLE；重复执行报 1050，属预期（与 001/002 一致）。
CREATE TABLE heron_production_context (
  context_id bigint NOT NULL,
  work_id bigint NOT NULL,
  version_id bigint NOT NULL,
  task_id bigint NOT NULL,
  context_no int NOT NULL COMMENT '同一作品的上下文序号，与版本号同源',
  parent_context_id bigint NULL COMMENT '上一版上下文；首版为空',
  revision_reason varchar(200) NULL COMMENT '为什么产生新版本：reserve / resume / manual-retry',
  schema_version varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_json json NOT NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (context_id),
  UNIQUE KEY uk_context_version (version_id),
  UNIQUE KEY uk_context_work_no (work_id, context_no),
  KEY ix_context_task (task_id),
  KEY ix_context_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
