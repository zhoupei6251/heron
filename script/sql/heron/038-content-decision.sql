-- 内容决策：一次研究给出的判断，加上人对它的处理。
-- 为什么另建表而不是只读 heron_research_task.report_json：
-- 研究是可重跑的（同一机会同一天一个任务，重跑覆盖同一行），而决策一旦产生就是历史事实——
-- 重跑不该改写「当时人看过什么、怎么定的」。所以这里存决策那一刻的快照，不存引用。
CREATE TABLE IF NOT EXISTS heron_content_decision (
  decision_id     bigint       NOT NULL,
  research_id     bigint       NOT NULL,
  attempt_no      int          NOT NULL,
  opportunity_id  bigint       NULL,
  -- Agent 给的判断
  decision_type   varchar(16)  NOT NULL COMMENT 'PRODUCE / OBSERVE / REJECT',
  confidence      decimal(5,4) NULL,
  decision_reason varchar(1000) NULL,
  -- 证据绑定：这一版的结论基于哪份数据
  evidence_ref    varchar(128) NULL COMMENT '报告里的 data_sha256；没有快照时为空',
  report_version  varchar(16)  NULL,
  prompt_version  varchar(64)  NULL,
  model_id        varchar(160) NULL,
  -- 人对这个判断的处理
  status          varchar(16)  NOT NULL COMMENT 'proposed 待处理 / accepted 采纳 / rejected 人工否决',
  decided_by      bigint       NULL,
  decided_at      datetime(3)  NULL,
  decision_note   varchar(1000) NULL,
  -- 独立核验员的判定：与研究员的判断分开存，冲突才有可能被看见
  verification_verdict   varchar(16) NULL COMMENT 'AGREE / DISAGREE / UNCERTAIN；未核验为空',
  verification_reason    varchar(1000) NULL,
  verification_conflicts json NULL,
  create_dept     bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by       bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (decision_id),
  -- 同一研究的同一次执行只产生一条决策：重跑产生新 attempt，不覆盖历史
  UNIQUE KEY uk_decision_research_attempt (research_id, attempt_no),
  KEY ix_decision_opportunity (opportunity_id),
  KEY ix_decision_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容决策：Agent 判断与人工处理';

-- 回滚：DROP TABLE heron_content_decision;
