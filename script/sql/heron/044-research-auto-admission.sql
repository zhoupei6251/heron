-- 研究候选进自动模式的逐源准入开关，以及候选上的来源 key。
--
-- 为什么不是「一个全局开关就放行」：业务规格要求自动模式必须逐源开启、可下架。
-- 研究候选的「源」是数据集源（heron_data_source），和新闻源（heron_news_source）是两套注册表，
-- 所以准入只能按数据源判。默认 '1'（不允许）：不显式打开，任何研究候选都不会进自动池。
--
-- 为什么 candidate 要单独存 source_key：准入判定要按源查，靠 evidence JSON 现解析
-- 会在证据形状变化时静默失效——那不会报错，只会让所有研究候选悄悄掉出准入范围。
--
-- 回滚：
--   ALTER TABLE heron_news_candidate DROP COLUMN source_key;
--   ALTER TABLE heron_data_source DROP COLUMN auto_enabled;

ALTER TABLE heron_data_source
  ADD COLUMN auto_enabled char(1) NOT NULL DEFAULT '1'
    COMMENT '是否允许自动发布：0 允许，1 不允许（默认不允许）' AFTER enabled;

ALTER TABLE heron_news_candidate
  ADD COLUMN source_key varchar(128) NULL
    COMMENT '研究候选的数据源 key；空=热榜来源' AFTER source_kind;
