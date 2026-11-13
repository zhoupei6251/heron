-- 机会要能被事后复核：把判断时看到的数据一起存下来。
-- 源会变（域名迁移、限流、下线），只存派生信号的话，这条机会以后就永久无法验证——
-- 实测遇到过：分析时拿到 65 个点，几十分钟后再取就是 0 个点，Agent 想核对也无从下手。
ALTER TABLE heron_data_opportunity
  ADD COLUMN snapshot_json longtext NULL COMMENT '判断时看到的数据快照：尾部至多 120 点，超限只留元信息';

-- 回滚：ALTER TABLE heron_data_opportunity DROP COLUMN snapshot_json;
