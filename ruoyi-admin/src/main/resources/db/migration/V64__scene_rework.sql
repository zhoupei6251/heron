-- 场景级返工审计：一次成功返工只记录一个 scene_id 与前后成片指纹。
-- 音轨逐包复制，render_calls=1、tts_calls=0 是数据库侧不变量；完整闸报告留在 checks_json。
-- 回滚：DROP TABLE heron_scene_rework;

CREATE TABLE heron_scene_rework (
  scene_rework_id           bigint       NOT NULL COMMENT '主键',
  version_id                bigint       NOT NULL COMMENT '返工后的视频版本 ID',
  previous_version_id       bigint       NOT NULL COMMENT '数据库锁定的上一版视频版本 ID',
  scene_id                  varchar(32)  NOT NULL COMMENT '稳定场景标识，如 scene_001',
  scene_index               int          NOT NULL COMMENT '场景在脚本中的零基下标',
  start_ms                  bigint       NOT NULL COMMENT '原时间轴窗口起点',
  end_ms                    bigint       NOT NULL COMMENT '原时间轴窗口终点',
  previous_video_sha256     char(64)     NOT NULL COMMENT '返工前成片 SHA-256',
  scene_video_sha256        char(64)     NOT NULL COMMENT '单屏渲染产物 SHA-256',
  video_sha256              char(64)     NOT NULL COMMENT '返工后成片 SHA-256',
  contact_sheet_sha256      char(64)     NOT NULL COMMENT '返工后联络表 SHA-256',
  script_sha256             char(64)     NOT NULL COMMENT '返工后脚本 JSON SHA-256',
  previous_script_sha256    char(64)     NOT NULL COMMENT '已校验上一版 script 产物 SHA-256',
  previous_manifest_sha256  char(64)     NOT NULL COMMENT '数据库上一版完整产物清单 SHA-256',
  audio_sha256              char(64)     NOT NULL COMMENT '原音轨逐包哈希，前后必须一致',
  render_calls              int          NOT NULL DEFAULT 1 COMMENT '目标屏渲染次数',
  tts_calls                 int          NOT NULL DEFAULT 0 COMMENT '返工配音次数',
  duration_ms               bigint       NOT NULL COMMENT '冻结的实测音频时长',
  checks_json               json         NOT NULL COMMENT '返工后完整闸报告',
  create_dept               bigint       DEFAULT NULL,
  create_by                 bigint       DEFAULT NULL,
  create_time               datetime(3)  NOT NULL,
  PRIMARY KEY (scene_rework_id),
  KEY ix_scene_rework_version (version_id, create_time),
  UNIQUE KEY uk_scene_rework_version (version_id),
  KEY ix_scene_rework_scene (version_id, scene_id),
  CONSTRAINT ck_scene_rework_window CHECK (end_ms > start_ms),
  CONSTRAINT ck_scene_rework_render_calls CHECK (render_calls = 1),
  CONSTRAINT ck_scene_rework_tts_calls CHECK (tts_calls = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视频场景级返工审计与指纹';
