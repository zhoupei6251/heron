-- 人工覆盖核验冲突的理由。
-- 核验判 DISAGREE 时提升默认被阻断；人要推翻这道闸必须留下理由，
-- 否则「有门禁」就只是句口号——事后看不出这次提升是有意放行还是漏过去了。
-- 操作者与时间由同一次裁决的 decided_by / decided_at 承担，不另存。
-- 回滚：ALTER TABLE heron_content_decision DROP COLUMN verification_override_reason;
ALTER TABLE heron_content_decision
  ADD COLUMN verification_override_reason varchar(1000) NULL
    COMMENT '人工覆盖核验冲突的理由；为空表示没有覆盖' AFTER verification_conflicts;
