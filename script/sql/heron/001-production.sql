-- Heron 统一生产执行底座。幂等迁移由 E2 在授权环境执行，本文件不在现有 heron 库直接运行。
CREATE TABLE heron_production_lane (
  biz_type varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (biz_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO heron_production_lane (biz_type,create_time,update_time)
VALUES ('news_video',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
       ('prototype',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));

CREATE TABLE heron_production_task (
  task_id bigint NOT NULL,
  biz_type varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  biz_id bigint NOT NULL, version_id bigint NOT NULL,
  owner_id bigint NOT NULL, actor_id bigint NOT NULL,
  request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_snapshot json NOT NULL,
  status varchar(16) NOT NULL, stage varchar(32) NULL, progress tinyint NOT NULL DEFAULT 0,
  attempt_no int NOT NULL DEFAULT 0,
  run_token char(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until datetime(3) NULL, heartbeat_at datetime(3) NULL,
  started_at datetime(3) NULL, finished_at datetime(3) NULL,
  cancel_requested_at datetime(3) NULL,
  error_code varchar(64) NULL, error_reason varchar(2000) NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (task_id),
  UNIQUE KEY uk_task_request (actor_id,biz_type,biz_id,request_key),
  UNIQUE KEY uk_task_version (biz_type,version_id),
  KEY ix_task_claim (biz_type,status,create_time,task_id),
  KEY ix_task_lease (status,lease_until),
  KEY ix_task_object (biz_type,biz_id,status),
  KEY ix_task_owner (owner_id,create_time,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE heron_task_step (
  step_id bigint NOT NULL, task_id bigint NOT NULL, attempt_no int NOT NULL,
  stage varchar(32) NOT NULL, status varchar(16) NOT NULL,
  started_at datetime(3) NOT NULL, finished_at datetime(3) NULL,
  input_locator varchar(256) NULL, error_code varchar(64) NULL,
  error_reason varchar(2000) NULL, evidence json NULL,
  checkpoint json NULL, runtime_identity json NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (step_id),
  UNIQUE KEY uk_step (task_id,attempt_no,stage),
  KEY ix_step_task (task_id,attempt_no,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE heron_usage_record (
  usage_id bigint NOT NULL, task_id bigint NOT NULL, attempt_no int NOT NULL,
  call_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  stage varchar(32) NOT NULL, provider varchar(80) NULL, model varchar(160) NULL,
  provider_call_id varchar(160) NULL,
  status varchar(16) NOT NULL, input_tokens bigint NULL, output_tokens bigint NULL,
  elapsed_ms bigint NULL, error_code varchar(64) NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (usage_id),
  UNIQUE KEY uk_usage_call (task_id,attempt_no,call_id),
  KEY ix_usage_task (task_id,attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
