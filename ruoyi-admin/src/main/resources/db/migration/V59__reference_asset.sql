-- 参考素材资产：只记录治理状态与 OSS 指针，视频字节不落 MySQL。
--
-- DISCOVERED → CHECKING → PENDING_AUTH / RESTRICTED / ACQUIRING → ACQUIRED / FAILED。
-- 未知授权默认停在 PENDING_AUTH；ACQUIRING 按单文件上限预留容量，避免并发突破总上限。
--
-- 回滚：DROP TABLE heron_reference_asset;

CREATE TABLE heron_reference_asset (
  reference_asset_id     bigint       NOT NULL COMMENT '主键',
  trend_id               bigint       NOT NULL COMMENT '来源热榜候选',
  platform               varchar(32)  NOT NULL COMMENT '平台标识',
  aweme_id               varchar(64)  NOT NULL COMMENT '平台作品 ID',
  status                 varchar(16)  NOT NULL COMMENT 'DISCOVERED/CHECKING/RESTRICTED/PENDING_AUTH/ACQUIRING/ACQUIRED/FAILED',
  license_status         varchar(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/ALLOWED/RESTRICTED',
  authorization_reason   varchar(1000) DEFAULT NULL COMMENT '人工授权或限制理由',
  authorized_by          bigint       DEFAULT NULL COMMENT '授权操作者',
  authorized_at          datetime(3)  DEFAULT NULL COMMENT '授权时间',
  oss_config_key         varchar(128) DEFAULT NULL COMMENT 'sys_oss_config 配置键',
  oss_key                varchar(1024) DEFAULT NULL COMMENT '原片对象键',
  oss_url                varchar(2048) DEFAULT NULL COMMENT '原片访问地址',
  file_size              bigint       DEFAULT NULL COMMENT '文件字节数',
  content_sha256         varchar(64)  DEFAULT NULL COMMENT '文件 SHA-256',
  media_type             varchar(128) DEFAULT NULL COMMENT '媒体类型',
  acquired_at            datetime(3)  DEFAULT NULL COMMENT '获取完成时间',
  attempt_count          int          NOT NULL DEFAULT 0 COMMENT '下载尝试次数',
  run_token              varchar(64)  DEFAULT NULL COMMENT '本次下载租约令牌',
  lease_until            datetime(3)  DEFAULT NULL COMMENT '租约截止时间',
  reserved_bytes         bigint       NOT NULL DEFAULT 0 COMMENT 'ACQUIRING 期间预留容量',
  failure_code           varchar(64)  DEFAULT NULL COMMENT '失败闸或错误码',
  failure_reason         varchar(2000) DEFAULT NULL COMMENT '可读失败原因',
  create_dept            bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
  update_by              bigint NULL, update_time datetime(3) NOT NULL,
  PRIMARY KEY (reference_asset_id),
  UNIQUE KEY uk_reference_asset_aweme (platform, aweme_id),
  KEY ix_reference_asset_status (status),
  KEY ix_reference_asset_trend (trend_id),
  CONSTRAINT ck_reference_asset_status CHECK (status IN
    ('DISCOVERED','CHECKING','RESTRICTED','PENDING_AUTH','ACQUIRING','ACQUIRED','FAILED')),
  CONSTRAINT ck_reference_asset_license CHECK (license_status IN ('UNKNOWN','ALLOWED','RESTRICTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参考视频素材资产与授权治理状态';
