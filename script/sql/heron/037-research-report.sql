-- 研究任务的结构化研究报告。
-- 存的不是结论文本（那在 result_text），而是下游可机读的形态：decision / keyInsights /
-- riskWarnings / 依据的快照标识。plan.md §8 要求「不能只保存查询时间而不保存数据版本」，
-- 快照的 data_sha256 就落在这一列里。
-- 回滚：ALTER TABLE heron_research_task DROP COLUMN report_json;
ALTER TABLE heron_research_task
  ADD COLUMN report_json json NULL COMMENT '结构化研究报告：结论、要点、风险与快照绑定，见 ResearchReport';
