package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronJevShadow;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * JEV SHADOW 对照记录展示对象。
 */
@Data
@AutoMapper(target = HeronJevShadow.class)
public class JevShadowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long shadowId;
    private String poolId;
    private String poolHash;
    private String localLine;
    private String jevLine;
    private Double jevConfidence;
    private Boolean agree;
    private String status;
    private String model;
    private Long latencyMs;
    private String errorCode;
    private LocalDateTime createTime;
}
