-- 研究任务的证据链。
-- 只存引用关系（哪条结论引用了哪次工具调用、哪些引用查不到），不存工具原始返回：
-- heron_agent_tool_call 里已经有入出参摘要，同一份数据存两遍迟早对不上。
-- 回滚：ALTER TABLE heron_research_task DROP COLUMN evidence_json;
ALTER TABLE heron_research_task
  ADD COLUMN evidence_json json NULL COMMENT '证据链：结论引用的调用与未核实引用，见 EvidenceCollector';
