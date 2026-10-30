package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 任务阶段轨迹，不单独作为可调度作业。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_task_step")
public class HeronTaskStep extends BaseEntity {

    @TableId(value = "step_id")
    private Long stepId;

    private Long taskId;

    private Integer attemptNo;

    private String stage;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String inputLocator;

    private String errorCode;

    private String errorReason;

    private String evidence;

    private String checkpoint;

    private String runtimeIdentity;
}
