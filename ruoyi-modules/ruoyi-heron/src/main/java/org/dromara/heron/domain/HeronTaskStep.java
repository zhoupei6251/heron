package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.IdType;
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

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

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

    /**
     * 阶段续跑检查点：列保留（历史数据仍可查），但写入路径已删除。
     *
     * <p>生产链路从来没有调用方，一个「看起来像已实现、其实没人用」的续跑能力比明说没有更危险。
     */
    private String checkpoint;

    private String runtimeIdentity;
}
