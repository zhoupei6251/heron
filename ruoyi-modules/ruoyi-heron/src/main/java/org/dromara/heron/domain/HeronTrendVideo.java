package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 抖音热点榜上采到的一条候选视频。
 *
 * <p>只存采集当时平台给的数，不做加工：热榜位次照抄平台顺序，不自己重排。
 * 播放量不存——抖音网页端恒为 0，存一列全 0 的数只会误导统计。
 */
@Data
@TableName("heron_trend_video")
public class HeronTrendVideo implements Serializable {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String platform;

    /** 平台作品 ID；与 platform 一起唯一，重复采集只更新热度不新增行。 */
    private String awemeId;

    /** 热榜位次，1 起；取首次出现顺序。 */
    private Integer rankNo;

    private String description;

    private String authorNickname;

    private String authorUniqueId;

    private Long authorFollowerCount;

    private Long durationMs;

    private Long diggCount;

    private Long commentCount;

    private Long shareCount;

    private Long collectCount;

    private Long recommendCount;

    /** 视频直链；采集当天有效，过期的直链只能丢。 */
    private String downloadUrl;

    private String coverUrl;

    private LocalDateTime publishedAt;

    private LocalDateTime collectedAt;

    private LocalDateTime createTime;

    /** 学习状态；历史行由数据库默认值补为 SEEN。 */
    private String learnState;

    /** 学习尝试次数、失败原因与产物位置，人可读。 */
    private String learnNote;

    /** 原片在 sys_oss 中的主键。 */
    private Long ossId;
}
