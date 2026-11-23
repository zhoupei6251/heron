-- 模型提出的策略假设：单独成表，因为每条都有自己的可证伪条件与生成依据。
--
-- 为什么不塞进 heron_auto_slot 或 heron_content_decision：那两张表记的是「当期怎么决定」，
-- 假设记的是「以后拿什么去验证」——一条决策可以对应多条假设，塞进 JSON 列就查不了也管不了。
--
-- 硬约束由表结构兜底：metric 是闭集，claim 与 falsify_if 都必填；
-- 没有可证伪条件的假设不该进这张表——它就只是一句猜测。
--
-- 回滚：
--   DROP TABLE heron_strategy_hypothesis;

CREATE TABLE heron_strategy_hypothesis (
  hypothesis_id bigint NOT NULL COMMENT '假设 id',
  schema_version varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '假设契约版本',
  claim varchar(500) NOT NULL COMMENT '一句策略判断；不得含预测性表述',
  dimension varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归因维度',
  bucket_value varchar(128) NOT NULL COMMENT '分组值；必须取自生成时的归因分组',
  metric varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '验证看哪个指标',
  min_samples int NOT NULL COMMENT '验证所需最少样本数',
  min_weeks int NOT NULL COMMENT '验证所需最少自然周数',
  falsify_if varchar(500) NOT NULL COMMENT '出现什么结果算被证伪',
  basis varchar(1000) NOT NULL COMMENT '生成时的样本依据：n、证据等级、当时的中位数',
  source varchar(200) NOT NULL COMMENT '模型与提示词版本',
  generated_by bigint NOT NULL COMMENT '谁触发了这次生成',
  generated_at datetime(3) NOT NULL,
  PRIMARY KEY (hypothesis_id),
  KEY ix_strategy_hypothesis_generated (generated_at),
  CONSTRAINT ck_strategy_hypothesis_metric CHECK (metric IN ('medianPlays','medianCompletionRate','outlierRatio'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型策略假设：只作待验证项，不得当结论用';
