package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对外阶段轨迹，checkpoint 和运行时身份不下发。
 */
@Data
public class ProductionStepVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String stage;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorCode;

    private String errorReason;

    private List<ProductionFileVo> evidence;
}
