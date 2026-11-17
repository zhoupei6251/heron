-- 研究 attempt 实际取到的数据快照。
-- 为什么需要：研究工具里调的是 DatasetFetchService.snapshot()，它不落产物；
-- 而模型的结论正是基于这份数据。不冻结的话，「这条结论基于哪一版数据」事后只能靠时间猜，
-- 源一变就再也核不出来（实测过同一条源几十分钟内从 65 个点变成 0 个点）。
-- 为什么存原始 JSON 而不是只存哈希：只存哈希能证明「数据变了」，却不能再看一眼当时是什么；
-- 快照点数被工具夹在 60 以内，一条几 KB，量级是「一天几条」，不值得为它先上一套对象存储。
-- 回滚：DROP TABLE heron_research_snapshot;
CREATE TABLE IF NOT EXISTS heron_research_snapshot (
  snapshot_id   bigint       NOT NULL,
  research_id   bigint       NOT NULL,
  attempt_no    int          NOT NULL,
  source_key    varchar(128) NOT NULL,
  data_sha256   char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  retrieved_at  varchar(32)  NULL,
  contract      varchar(32)  NULL,
  points        int          NULL,
  snapshot_json longtext     NOT NULL,
  create_dept   bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by     bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (snapshot_id),
  -- 一次 attempt 里同一个源只留第一份：同源两次取数版本已经不同的话，
  -- 那本身就说明这轮结论可疑，留第一份足够定位问题
  UNIQUE KEY uk_snapshot_attempt_source (research_id, attempt_no, source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究 attempt 实际使用的数据快照';
