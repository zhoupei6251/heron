package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 一次用户生成请求的执行账本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_production_task")
public class HeronProductionTask extends BaseEntity {

    @TableId(value = "task_id")
    private Long taskId;

    private String bizType;

    private Long bizId;

    private Long versionId;

    private Long ownerId;

    private Long actorId;

    private String requestKey;

    private String requestHash;

    private String inputHash;

    private String inputSnapshot;

    private String status;

    private String stage;

    private Integer progress;

    private Integer attemptNo;

    private String runToken;

    private LocalDateTime leaseUntil;

    private LocalDateTime heartbeatAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime cancelRequestedAt;

    private String errorCode;

    private String errorReason;
}
