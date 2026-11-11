-- 自动模式（2026-11-06）：源级开关、稿件自动发布标记、槽位幂等记录。
-- 约定沿用基座：char(1) 的 '0' 表示开启/'1' 表示关闭。

ALTER TABLE heron_news_source
  ADD COLUMN auto char(1) NOT NULL DEFAULT '1' COMMENT '源级自动产出开关：0 开 1 关，新源默认关' AFTER enabled;

-- 自动模式认领的稿件：由自动发布扫描负责确认，人工模式保持 1（关）。
ALTER TABLE heron_news_work
  ADD COLUMN auto_publish char(1) NOT NULL DEFAULT '1' COMMENT '自动模式认领：0 开 1 关' AFTER active_task_id;

CREATE TABLE IF NOT EXISTS heron_auto_slot (
  slot_date date NOT NULL,
  slot_time varchar(64) NOT NULL,
  status varchar(16) NOT NULL,
  detail varchar(2000) NULL,
  work_id bigint NULL,
  create_time datetime(3) NOT NULL,
  update_time datetime(3) NOT NULL,
  PRIMARY KEY (slot_date, slot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动产出槽位：日期+时刻唯一，重复触发不重复产出';
