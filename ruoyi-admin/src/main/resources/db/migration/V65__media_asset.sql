-- V5 画面素材：只存元数据与 OSS 指针；图片字节不落 MySQL。
-- 许可白名单与 UNKNOWN 拒绝在入库层和 selector 两层执行，数据库约束再兜底。
-- 逻辑删除只让素材退出后续选材，不删除 OSS 对象，避免历史作品资产清单失效。
--
-- 回滚：删除本迁移新增的 sys_menu 行后 DROP TABLE heron_media_asset;

CREATE TABLE heron_media_asset (
  id                    bigint        NOT NULL COMMENT '主键',
  name                  varchar(100)  NOT NULL COMMENT '素材名称',
  asset_kind            varchar(16)   NOT NULL DEFAULT 'IMAGE' COMMENT '素材类型，当前接入模板图片位',
  source_uri            varchar(2048) NOT NULL COMMENT '素材来源地址',
  license_code          varchar(32)   NOT NULL COMMENT 'OWNED/PUBLIC_DOMAIN/CC0/CC_BY/CC_BY_SA',
  license_evidence_uri  varchar(2048) NOT NULL COMMENT '许可证明地址',
  attribution           varchar(1000) DEFAULT NULL COMMENT '署名要求',
  tags                  varchar(500)  DEFAULT NULL COMMENT '场景关键词，逗号分隔',
  width                 int           NOT NULL COMMENT '图像宽度',
  height                int           NOT NULL COMMENT '图像高度',
  orientation           varchar(16)   NOT NULL COMMENT 'PORTRAIT/LANDSCAPE/SQUARE，由尺寸推导',
  content_sha256        varchar(64)   NOT NULL COMMENT '文件 SHA-256',
  size_bytes            bigint        NOT NULL COMMENT '文件字节数',
  media_type            varchar(128)  NOT NULL COMMENT 'MIME，仅 PNG/JPEG',
  oss_config_key        varchar(128)  NOT NULL COMMENT 'sys_oss_config 配置键',
  oss_key               varchar(1024) NOT NULL COMMENT 'OSS 对象键',
  oss_url               varchar(2048) NOT NULL COMMENT 'OSS 访问地址',
  del_flag              char(1)       NOT NULL DEFAULT '0' COMMENT '0 正常、1 删除',
  create_dept           bigint NULL,
  create_by             bigint NULL,
  create_time           datetime(3) NOT NULL,
  update_by             bigint NULL,
  update_time           datetime(3) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_media_asset_license_kind (license_code, asset_kind, del_flag),
  KEY ix_media_asset_tags (tags),
  KEY ix_media_asset_sha256 (content_sha256),
  KEY ix_media_asset_create_time (create_time),
  CONSTRAINT ck_media_asset_kind CHECK (asset_kind = 'IMAGE'),
  CONSTRAINT ck_media_asset_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
  CONSTRAINT ck_media_asset_license CHECK (license_code IN ('OWNED','PUBLIC_DOMAIN','CC0','CC_BY','CC_BY_SA')),
  CONSTRAINT ck_media_asset_attribution CHECK (license_code NOT IN ('CC_BY','CC_BY_SA')
    OR CHAR_LENGTH(TRIM(attribution)) > 0),
  CONSTRAINT ck_media_asset_source_uri CHECK (CHAR_LENGTH(TRIM(source_uri)) > 0
    AND (LOWER(source_uri) LIKE 'http://%' OR LOWER(source_uri) LIKE 'https://%')),
  CONSTRAINT ck_media_asset_license_evidence_uri CHECK (CHAR_LENGTH(TRIM(license_evidence_uri)) > 0
    AND (LOWER(license_evidence_uri) LIKE 'http://%' OR LOWER(license_evidence_uri) LIKE 'https://%')),
  CONSTRAINT ck_media_asset_dimensions CHECK (width > 0 AND height > 0),
  CONSTRAINT ck_media_asset_orientation CHECK (orientation IN ('PORTRAIT','LANDSCAPE','SQUARE')),
  CONSTRAINT ck_media_asset_sha256 CHECK (content_sha256 REGEXP '^[0-9a-fA-F]{64}$'),
  CONSTRAINT ck_media_asset_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
  CONSTRAINT ck_media_asset_media_type CHECK (media_type IN ('image/png','image/jpeg')),
  CONSTRAINT ck_media_asset_oss_pointer CHECK (CHAR_LENGTH(TRIM(oss_config_key)) > 0
    AND CHAR_LENGTH(TRIM(oss_key)) > 0 AND CHAR_LENGTH(TRIM(oss_url)) > 0),
  CONSTRAINT ck_media_asset_del_flag CHECK (del_flag IN ('0','1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视频画面素材与生产许可';

-- 菜单沿用脚本中的 Heron 保留段；动态路由由 sys_menu 驱动，不在前端 router 写死业务路由。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002120, '画面素材', 1761400000000002000, 12, 'media', 'heron/asset/media/index', '',
  'N', 'Y', 'C', '0', '0', 'heron:mediaAsset:list', 'picture', '', 1761000000000000103,
  sysdate(), null, null, 'B-roll 图片入库、许可治理与场景选材'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002000);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002121, '素材查询', 1761400000000002120, 1, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:mediaAsset:query', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002120);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002122, '素材入库', 1761400000000002120, 2, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:mediaAsset:add', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002120);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002123, '素材修改', 1761400000000002120, 3, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:mediaAsset:edit', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002120);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002124, '素材删除', 1761400000000002120, 4, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:mediaAsset:remove', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002120);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002125, '场景选材', 1761400000000002120, 5, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:mediaAsset:select', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002120);
