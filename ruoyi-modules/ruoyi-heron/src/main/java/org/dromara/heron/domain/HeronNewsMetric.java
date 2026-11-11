package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一次发布表现采样：同一版本可以有多条，取 fetched_at 最新的一条作为当前表现。 */
@Data
@TableName("heron_news_metric")
public class HeronNewsMetric implements Serializable {
    @TableId
    private Long metricId;
    private Long versionId;
    private String platform;
    private Long plays;
    private BigDecimal completionRate;
    private Long likes;
    private Long comments;
    private Long shares;
    private LocalDateTime fetchedAt;
    private Long createBy;
    private LocalDateTime createTime;
}
