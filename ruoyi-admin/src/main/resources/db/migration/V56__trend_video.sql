-- 市面爆款采集：把抖音热点榜上的视频按热榜位次存下来，供后续拆解「别人怎么做的」。
--
-- 为什么不存播放量：抖音网页端接口里 play_count 恒为 0，这个数拿不到，所以不设这一列，
-- 免得日后有人拿一列全 0 的值做统计。
-- 为什么不自己算分：热榜顺序本身就是平台算过的热度序，先照抄，等拆解样本够了再谈评分。
--
-- 唯一键 (platform, aweme_id)：同一支视频每天都会再采到一次，重复采集只更新热度，不新增行。
--
-- 回滚：DROP TABLE heron_trend_video;

CREATE TABLE heron_trend_video (
  trend_id              bigint       NOT NULL COMMENT '主键',
  platform              varchar(32)  NOT NULL COMMENT '平台标识，目前只有 douyin',
  aweme_id              varchar(64)  NOT NULL COMMENT '平台作品 ID',
  rank_no               int          DEFAULT NULL COMMENT '热榜位次，1 起；首次出现顺序，不重排',
  description           varchar(500) DEFAULT NULL COMMENT '作品文案',
  author_nickname       varchar(128) DEFAULT NULL COMMENT '作者昵称',
  author_unique_id      varchar(128) DEFAULT NULL COMMENT '作者抖音号',
  author_follower_count bigint       DEFAULT NULL COMMENT '作者粉丝数；接口不给时为空',
  duration_ms           bigint       DEFAULT NULL COMMENT '时长（毫秒）',
  digg_count            bigint       DEFAULT NULL COMMENT '点赞数',
  comment_count         bigint       DEFAULT NULL COMMENT '评论数',
  share_count           bigint       DEFAULT NULL COMMENT '分享数',
  collect_count         bigint       DEFAULT NULL COMMENT '收藏数',
  recommend_count       bigint       DEFAULT NULL COMMENT '推荐数',
  download_url          varchar(1024) DEFAULT NULL COMMENT '视频直链，采集当天有效',
  cover_url             varchar(1024) DEFAULT NULL COMMENT '封面地址',
  published_at          datetime(3)  DEFAULT NULL COMMENT '作品发布时间',
  collected_at          datetime(3)  NOT NULL COMMENT '本次采集时间',
  create_time           datetime(3)  NOT NULL COMMENT '首次入库时间',
  PRIMARY KEY (trend_id),
  UNIQUE KEY uk_trend_aweme (platform, aweme_id),
  KEY ix_trend_collected (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='抖音热点榜采集到的候选视频';
