package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 抖音热点榜上的一个热词（话题层）。
 *
 * <p>和 {@link HeronTrendVideo} 是两层：热词是「今天大家在聊什么」，作品是
 * 「别人怎么拍的」。一个热词下可能挂着几十支作品，所以不合并成一张表。
 *
 * <p>{@code viewCount} / {@code videoCount} 来自页面的 {@code detail_list=1} 参数，
 * 是别处拿不到的数；但置顶条没有这两个值，判空再用于统计。
 */
@Data
@TableName("heron_trend_hotword")
public class HeronTrendHotword implements Serializable {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String platform;

    /** 热词文本；与 platform 一起唯一，重复采集只更新热度不新增行。 */
    private String word;

    /** 本次热榜位次，1 起；置顶条没有位次。 */
    private Integer rankNo;

    /** 历史最好位次，越小越热；只降不升。 */
    private Integer bestRank;

    private Long hotValue;

    /** 浏览量；置顶条为空。 */
    private Long viewCount;

    /** 相关视频数；置顶条为空，个别词为 0。 */
    private Long videoCount;

    /** 连续采到的次数：连着重采到几次比单次快照更能说明它在持续升温。 */
    private Long hitCount;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createTime;
}
