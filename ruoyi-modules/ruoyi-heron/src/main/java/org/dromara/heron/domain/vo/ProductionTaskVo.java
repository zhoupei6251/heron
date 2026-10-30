package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务中心视图，不包含令牌、快照和运行时细节。
 */
@Data
@AutoMapper(target = HeronProductionTask.class)
public class ProductionTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long taskId;

    private ProductionBizType bizType;

    private Long bizId;

    private Long versionId;

    private ProductionStatus status;

    private String stage;

    private Integer progress;

    private Integer attemptNo;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorCode;

    private String errorReason;

    private List<ProductionStepVo> steps;

    private List<ProductionUsageVo> usage;

    private List<ProductionFileVo> artifacts;
}
