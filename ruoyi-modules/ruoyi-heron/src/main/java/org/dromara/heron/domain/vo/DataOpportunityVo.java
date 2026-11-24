package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronDataOpportunity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AutoMapper(target = HeronDataOpportunity.class)
public class DataOpportunityVo implements Serializable {
    private Long id;
    private String sourceKey;
    private String entity;
    private String metric;
    private String shape;
    private String cadence;
    private Integer windowPoints;
    private Integer points;
    private String periodStart;
    private String periodEnd;
    private BigDecimal score;
    private String signals;
    private String headline;
    private String status;
    private String rejectReason;
    private Long usedVersionId;
    private LocalDateTime observedAt;
    private LocalDateTime createTime;
}
