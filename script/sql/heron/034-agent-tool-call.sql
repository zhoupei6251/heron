-- 工具调用账本：一次研究里调了哪个工具、耗时多少、返回多大。
-- 不复用 sys_oper_log：那是记「人做了什么」的，研究里几十次工具调用会把人操作记录淹掉。
-- 只存摘要，不存原始返回——工具返回可能含业务数据，plan.md §9.3 要求脱敏且不无条件留全量。
CREATE TABLE IF NOT EXISTS heron_agent_tool_call (
  call_id          bigint       NOT NULL,
  research_id      bigint       NOT NULL,
  attempt_no       int          NOT NULL,
  round_no         int          NOT NULL DEFAULT 0,
  tool_name        varchar(64)  NOT NULL,
  tool_use_id      varchar(128) NULL,
  request_summary  varchar(2000) NULL,
  response_summary varchar(2000) NULL,
  status           varchar(16)  NOT NULL,
  duration_ms      bigint       NULL,
  error_code       varchar(64)  NULL,
  create_dept      bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by        bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (call_id),
  KEY ix_tool_call_research (research_id, attempt_no, round_no),
  KEY ix_tool_call_name (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究过程中的工具调用记录';

-- 回滚：DROP TABLE heron_agent_tool_call;
