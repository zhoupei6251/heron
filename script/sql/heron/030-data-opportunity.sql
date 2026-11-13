-- 数据机会：从注册表里的公开数据算出来的「今天哪一项变了、变了多少」。
-- 只落信号与确定性评分；标题由程序按信号拼，模型不参与算数，也不在第一步参与。
-- 同一源同一天同一信号只保留一行（dedup_key 唯一），重复分析走更新而不是追加。
-- 幂等：首次建表裸写 CREATE TABLE；重复执行报 1050，属预期。
-- 回滚：DROP TABLE heron_data_opportunity;（本表不被任何既有表引用，删除不影响生产链路）
CREATE TABLE heron_data_opportunity (
  opportunity_id bigint NOT NULL,
  source_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  entity varchar(128) NOT NULL DEFAULT '' COMMENT '序列名；单序列源为空串',
  metric varchar(64) NULL COMMENT 'dataset 快照里的 metric.key',
  shape varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'wide / long / rows / geo',
  cadence varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  window_points int NOT NULL COMMENT '分析请求的窗口点数，与 cadence 一起解释',
  points int NOT NULL COMMENT '实际拿到的点数',
  period_start varchar(32) NULL,
  period_end varchar(32) NULL,
  score decimal(6,2) NOT NULL COMMENT '确定性评分 0-100',
  signals longtext NOT NULL COMMENT '信号 JSON：变化率、z-score、新极值等',
  headline varchar(200) NOT NULL COMMENT '按信号拼的标题，不含模型表述',
  status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'new' COMMENT 'new / rejected',
  reject_reason varchar(500) NULL,
  used_version_id bigint NULL COMMENT '被哪条视频用了；第二步接入生产后写',
  dedup_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at datetime(3) NOT NULL,
  create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (opportunity_id),
  UNIQUE KEY uk_opportunity_dedup (dedup_key),
  KEY ix_opportunity_recent (status, score),
  KEY ix_opportunity_source (source_key, observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据机会：公开数据的确定性信号与评分';
