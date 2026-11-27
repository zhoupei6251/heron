-- 发布库存池：所有视频线的成片出口。评估状态与发布状态分两个维度，
-- 同一 version 只有一条库存（uk）；新版本入池时旧条目由 Java 标 SUPERSEDED。
CREATE TABLE IF NOT EXISTS heron_publish_inventory (
    id                  BIGINT       NOT NULL COMMENT '主键',
    version_id          BIGINT       NOT NULL COMMENT '不可变视频版本',
    work_id             BIGINT       NOT NULL COMMENT '作品',
    candidate_id        BIGINT       NULL COMMENT '原始选题',
    biz_line            VARCHAR(32)  NOT NULL COMMENT '内容生产线',
    duration_ms         BIGINT       NOT NULL COMMENT '成片时长',
    curation_status     VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '库存评估：pending/eligible/hold/rejected/expired',
    publish_status      VARCHAR(16)  NOT NULL DEFAULT 'waiting' COMMENT '发布：waiting/reserved/publishing/unknown/published/withdrawn/superseded',
    priority_score      INT          NULL COMMENT 'Java 计算的最终优先分',
    score_breakdown     TEXT         NULL COMMENT 'AI 评分明细 JSON',
    curation_reason     VARCHAR(2000) NULL COMMENT 'AI 保留或淘汰理由',
    curator_version     VARCHAR(64)  NULL COMMENT '评估 Agent/提示词版本',
    evaluated_at        DATETIME     NULL,
    expires_at          DATETIME     NULL COMMENT '内容有效期',
    reserved_at         DATETIME     NULL,
    publish_attempt_id  BIGINT       NULL COMMENT '已有发布尝试',
    del_flag            CHAR(1)      DEFAULT '0',
    create_dept         BIGINT       NULL,
    create_by           BIGINT       NULL,
    create_time         DATETIME     NOT NULL,
    update_by           BIGINT       NULL,
    update_time         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_publish_inventory_version (version_id),
    KEY idx_publish_inventory_pick (curation_status, publish_status, priority_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布库存池';
