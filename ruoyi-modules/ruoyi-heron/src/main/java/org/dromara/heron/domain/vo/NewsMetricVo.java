package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class NewsMetricVo implements Serializable {
    private Long metricId;
    private Long versionId;
    private String platform;
    private Long plays;
    private BigDecimal completionRate;
    private Long likes;
    private Long comments;
    private Long shares;
    private LocalDateTime fetchedAt;
}
