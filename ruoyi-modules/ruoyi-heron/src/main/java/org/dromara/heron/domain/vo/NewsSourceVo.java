package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronNewsSource;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 来源状态，不返回 fetchToken。 */
@Data
@AutoMapper(target = HeronNewsSource.class)
public class NewsSourceVo implements Serializable {
    private Long sourceId;
    private String name;
    private String sourceType;
    private String url;
    private Integer intervalSeconds;
    private String enabled;
    /** 源级自动产出开关：'0' 开 '1' 关。 */
    private String auto;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime nextFetchAt;
    private Integer failureCount;
    private String errorCode;
    private String errorReason;
}
