-- 人工放行「证据链不完整」的理由。
-- 为什么需要：研究报告的证据链会自评 complete（提交了结论且模型声称的引用都能在账本里找到）。
-- complete=false 意味着这条判断的某些依据不在本次 attempt 的调用记录里——可能是跨 attempt 的会话记忆，
-- 也可能是编造。两种情况都不该由系统默默放行，也不该硬拦到没法办事，所以由人看一眼并留理由。
-- 操作者与时间复用同一次裁决的 decided_by / decided_at，不另存。
-- 回滚：ALTER TABLE heron_content_decision DROP COLUMN evidence_override_reason;
ALTER TABLE heron_content_decision
  ADD COLUMN evidence_override_reason varchar(1000) NULL
    COMMENT '人工放行证据链不完整的理由；为空表示没有覆盖' AFTER verification_override_reason;
