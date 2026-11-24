-- 抖音热词：热点榜的「话题」层，和 heron_trend_video 的「作品」层是两件事。
--
-- 为什么单独一张表：热词是选题的线索（今天大家在聊什么），作品是拆解的样本
-- （别人怎么拍的）。一个热词下面可能有几十支作品，混在一张表里两边都别扭。
--
-- 为什么存 view_count / video_count：页面请求自带 detail_list=1&source=6，响应里
-- 就有这两个数，而别的口径拿不到——抖音网页端的 play_count 恒为 0。采集脚本
-- 早先只挑了 word/hot_value，把它们丢了，这里补上。
-- 注意 video_count 可能是 0 或空（置顶条就没有），不要当成「没有作品」。
--
-- 唯一键 (platform, word)：同一个词每次采集都会再出现，重复采集只更新热度与位次。
-- 保留 hit_count / best_rank / first_seen_at：一个词连着重采到几次，比单次快照
-- 更有信息量——这就是 HeronNewsCandidate 已经在用的那套判持续性的口径。
--
-- 回滚：DROP TABLE heron_trend_hotword;

CREATE TABLE heron_trend_hotword (
  id             bigint       NOT NULL COMMENT '主键',
  platform       varchar(32)  NOT NULL COMMENT '平台标识，目前只有 douyin',
  word           varchar(200) NOT NULL COMMENT '热词文本',
  rank_no        int          DEFAULT NULL COMMENT '本次热榜位次，1 起；置顶条可能为空',
  best_rank      int          DEFAULT NULL COMMENT '历史最好位次，越小越热',
  hot_value      bigint       DEFAULT NULL COMMENT '平台给的热度值',
  view_count     bigint       DEFAULT NULL COMMENT '浏览量；置顶条为空',
  video_count    bigint       DEFAULT NULL COMMENT '相关视频数；置顶条为空',
  hit_count      bigint       NOT NULL DEFAULT 1 COMMENT '连续采到的次数，判持续性用',
  first_seen_at  datetime(3)  NOT NULL COMMENT '第一次采到的时间',
  last_seen_at   datetime(3)  NOT NULL COMMENT '最近一次采到的时间',
  create_time    datetime(3)  NOT NULL COMMENT '首次入库时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_hotword_word (platform, word),
  KEY ix_hotword_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='抖音热点榜的热词（话题层）';
