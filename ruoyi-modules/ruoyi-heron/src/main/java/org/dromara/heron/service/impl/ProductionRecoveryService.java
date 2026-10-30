package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.IProductionRecoveryPolicy;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 中止态收尾：先确认旧执行已停止，再决定取消、重排或终结；无法确认就保持占用。
 */
@Slf4j
@Service
public class ProductionRecoveryService {

    private static final int MAX_ATTEMPTS = 2;
    private static final int RECOVERY_BATCH = 50;

    private final HeronProductionTaskMapper taskMapper;
    private final IProductionExecutionService execution;
    private final ProductionProcessService processes;
    private final ProductionUsageService usage;
    private final ProductionRuntimeIdentityService identities;
    private final ApplicationContext pipelines;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionRecoveryService(HeronProductionTaskMapper taskMapper,
                                     IProductionExecutionService execution,
                                     ProductionProcessService processes,
                                     ProductionUsageService usage,
                                     ProductionRuntimeIdentityService identities,
                                     ApplicationContext pipelines) {
        this(taskMapper, execution, processes, usage, identities, pipelines, Clock.systemUTC());
    }

    public ProductionRecoveryService(HeronProductionTaskMapper taskMapper,
                                     IProductionExecutionService execution,
                                     ProductionProcessService processes,
                                     ProductionUsageService usage,
                                     ProductionRuntimeIdentityService identities,
                                     ApplicationContext pipelines,
                                     Clock clock) {
        this.taskMapper = taskMapper;
        this.execution = execution;
        this.processes = processes;
        this.usage = usage;
        this.identities = identities;
        this.pipelines = pipelines;
        this.clock = clock;
    }

    public int recoverCancelling() {
        List<HeronProductionTask> candidates = taskMapper.selectExpiredCancelling(LocalDateTime.now(clock), RECOVERY_BATCH);
        int transitioned = 0;
        for (HeronProductionTask candidate : candidates) {
            try {
                if (recoverOne(candidate.getTaskId())) {
                    transitioned++;
                }
            } catch (RuntimeException e) {
                log.warn("恢复中止态任务失败，保留现状态: taskId={}", candidate.getTaskId(), e);
            }
        }
        return transitioned;
    }

    /**
     * worker 结束流水线后的即时收尾；租约未过期也适用，安全条件与恢复扫描一致。
     */
    public boolean finishCancelledRun(ProductionRunVo run) {
        HeronProductionTask task = taskMapper.selectById(run.taskId());
        if (task == null
            || !ProductionStatus.cancelling.name().equals(task.getStatus())
            || !run.runToken().equals(task.getRunToken())
            || task.getAttemptNo() != run.attemptNo()) {
            return false;
        }
        // 无运行身份说明监护进程从未登记成功；启动阶段已保证这种情况不留下子进程。
        String identity = identities.latest(run.taskId(), run.attemptNo());
        if (identity != null && !processes.stopAndConfirm(run)) {
            log.warn("取消收尾无法确认旧进程组已结束: taskId={}", run.taskId());
            return false;
        }
        long startedUsage = usage.startedCount(run.taskId(), run.attemptNo());
        if (startedUsage > 0) {
            usage.markAttemptUnknown(run);
        }
        return execution.finishCancellation(run, true, startedUsage > 0);
    }

    private boolean recoverOne(long taskId) {
        HeronProductionTask task = taskMapper.selectById(taskId);
        if (task == null
            || !ProductionStatus.cancelling.name().equals(task.getStatus())
            || task.getRunToken() == null
            || task.getLeaseUntil() == null
            || !task.getLeaseUntil().isBefore(LocalDateTime.now(clock))) {
            return false;
        }
        ProductionBizType type = ProductionBizType.valueOf(task.getBizType());
        ProductionRunVo run = new ProductionRunVo(task.getTaskId(), type, task.getBizId(), task.getVersionId(),
            task.getAttemptNo(), task.getRunToken(), task.getInputSnapshot(), null);
        // 无运行身份说明监护进程从未登记成功；启动阶段已保证这种情况不留下子进程。
        String identity = identities.latest(taskId, task.getAttemptNo());
        if (identity != null && !processes.stopAndConfirm(run)) {
            log.warn("恢复无法确认旧进程组已结束，保持中止态与占用: taskId={}", taskId);
            return false;
        }
        long startedUsage = usage.startedCount(taskId, task.getAttemptNo());

        if (task.getCancelRequestedAt() != null) {
            if (startedUsage > 0) {
                usage.markAttemptUnknown(run);
            }
            return execution.finishCancellation(run, true, startedUsage > 0);
        }
        if (startedUsage > 0) {
            usage.markAttemptUnknown(run);
            return execution.abandonRecovered(run, "external_outcome_unknown", "外部结果未知，已按失败处理");
        }
        if (task.getAttemptNo() < MAX_ATTEMPTS) {
            IProductionPipeline pipeline = pipeline(type);
            if (pipeline instanceof IProductionRecoveryPolicy policy && policy.canResume(run)) {
                return execution.requeueRecovered(run);
            }
        }
        return execution.abandonRecovered(run, "production_recovery_blocked", "无法确认可安全续跑，已终止");
    }

    private IProductionPipeline pipeline(ProductionBizType type) {
        String beanName = type == ProductionBizType.news_video
            ? "newsProductionPipeline" : "prototypeProductionPipeline";
        try {
            return pipelines.getBean(beanName, IProductionPipeline.class);
        } catch (org.springframework.beans.BeansException e) {
            return null;
        }
    }
}
