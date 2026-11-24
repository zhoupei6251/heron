package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronTrendVideo;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 抖音热点候选视频展示对象。
 */
@Data
@AutoMapper(target = HeronTrendVideo.class)
public class TrendVideoVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long trendId;
    private String platform;
    private String awemeId;
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
    private String coverUrl;
    private LocalDateTime publishedAt;
    private LocalDateTime collectedAt;

    /** 是否短到能被当成一条短视频；长片不下载也不拆解。 */
    private Boolean shortEnough;
}
