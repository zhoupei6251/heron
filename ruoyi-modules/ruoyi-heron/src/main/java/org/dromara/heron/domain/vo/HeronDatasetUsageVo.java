package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronDatasetUsage;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AutoMapper(target = HeronDatasetUsage.class)
public class HeronDatasetUsageVo implements Serializable {
    private Long usageId;
    private String sourceKey;
    private String entity;
    private String metric;
    private Long versionId;
    private LocalDateTime usedAt;
}
