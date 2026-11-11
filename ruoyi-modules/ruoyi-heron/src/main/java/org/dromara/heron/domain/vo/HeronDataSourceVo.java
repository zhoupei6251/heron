package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronDataSource;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AutoMapper(target = HeronDataSource.class)
public class HeronDataSourceVo implements Serializable {
    private String sourceKey;
    private String name;
    private String category;
    private String endpoint;
    private String historyEndpoint;
    private String httpMethod;
    private String dataFormat;
    private String cadence;
    private Integer historyDays;
    private String commercialOk;
    private String termsUrl;
    private String shapes;
    private String enabled;
    private String probeStatus;
    private String probeNote;
    private LocalDateTime lastUpdated;
    private Integer failureCount;
}
