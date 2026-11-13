-- 研究任务：一次研究 = 一次 Agent 会话。
-- 为什么不复用 heron_production_task：那个是「1 版本 1 任务」的生产底座，而研究是「1 次研究 : N 个候选」，
-- 且研究要带预算、可重跑、可只重跑某一步。状态词表与生产任务保持一致，只多一个 partial。
CREATE TABLE IF NOT EXISTS heron_research_task (
  research_id    bigint       NOT NULL,
  research_no    varchar(32)  NOT NULL,
  opportunity_id bigint       NULL,
  owner_id       bigint       NOT NULL,
  actor_id       bigint       NOT NULL,
  goal           varchar(1000) NOT NULL,
  snapshot_ref   varchar(256) NULL,
  grade          varchar(4)   NOT NULL DEFAULT 'L2',
  status         varchar(16)  NOT NULL,
  decision_type  varchar(16)  NULL,
  score          decimal(6,2) NULL,
  confidence     decimal(5,4) NULL,
  rounds         int          NOT NULL DEFAULT 0,
  tool_calls     int          NOT NULL DEFAULT 0,
  input_tokens   bigint       NULL,
  output_tokens  bigint       NULL,
  session_id     varchar(128) NULL,
  prompt_version char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  attempt_no     int          NOT NULL DEFAULT 0,
  lease_until    datetime(3)  NULL,
  heartbeat_at   datetime(3)  NULL,
  result_text    longtext     NULL,
  error_code     varchar(64)  NULL,
  error_reason   varchar(2000) NULL,
  -- 同一机会同一天只研究一次：重跑走 attempt_no，不新建行
  dedup_key      char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_dept    bigint       NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by      bigint       NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (research_id),
  UNIQUE KEY uk_research_no (research_no),
  UNIQUE KEY uk_research_dedup (dedup_key),
  KEY ix_research_claim (status, create_time, research_id),
  KEY ix_research_lease (status, lease_until),
  KEY ix_research_opportunity (opportunity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究任务：一次研究对应一次 Agent 会话';

-- 回滚：DROP TABLE heron_research_task;
