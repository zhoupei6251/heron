-- JEV SHADOW 对照记录：总编辑判完之后，把同一份候选概况再发给外部判断器，只记差异。
--
-- 为什么不复用 pool_json：那一列前端在读，键结构受约束；对照数据要能独立查询、独立清理，
-- 回滚时只掉这张表，不留残留。
--
-- 硬约束由表结构兜底：status 是闭集；失败、超时、预算耗尽一律只能记 undetermined，
-- 不得写成 ok——未定不等于 PASS。
--
-- 回滚：DROP TABLE heron_jev_shadow;

CREATE TABLE heron_jev_shadow (
  shadow_id      bigint       NOT NULL,
  pool_id        varchar(64)  NOT NULL COMMENT '候选池 id，对应 pool_json 的同名键',
  pool_hash      varchar(64)  NOT NULL COMMENT '候选池快照哈希：同哈希的本地判断与外部判断才可比',
  local_line     varchar(32)  NOT NULL COMMENT '本地总编辑实际选的线；NO_PUBLISH 表示今天不发',
  jev_line       varchar(32)  NULL COMMENT '外部判断选的线；未定时为空',
  jev_confidence double       NULL COMMENT '外部判断对该选项的置信度',
  agree          tinyint      NULL COMMENT '选线是否一致；未定时为空',
  status         varchar(16)  NOT NULL COMMENT 'ok 或 undetermined',
  model          varchar(64)  NULL COMMENT '实际调用的模型',
  latency_ms     bigint       NULL,
  error_code     varchar(64)  NULL,
  create_dept    bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by      bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (shadow_id),
  KEY ix_jev_shadow_pool (pool_id),
  KEY ix_jev_shadow_time (create_time),
  CONSTRAINT ck_jev_shadow_status CHECK (status IN ('ok','undetermined'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JEV SHADOW 对照记录：只作校准数据，不进任何决策';
