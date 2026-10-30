package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.HeronTaskStep;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.bo.ProductionFailureBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronProductionLaneMapper;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronTaskStepMapper;
import org.dromara.heron.service.IProductionDomain;
import org.dromara.heron.service.IProductionExecutionService;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行领取、租约、阶段回执、终结和过期回收。
 */
@Service
@Slf4j
public class ProductionExecutionServiceImpl implements IProductionExecutionService {

    private static final int LEASE_SECONDS = 60;
    private static final int RECOVERY_BATCH = 50;
    private static final int ERROR_REASON_LIMIT = 2000;
    private static final int ERROR_CODE_LIMIT = 64;
    private static final int INPUT_LOCATOR_LIMIT = 256;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronProductionTaskMapper taskMapper;
    private final HeronProductionLaneMapper laneMapper;
    private final HeronTaskStepMapper stepMapper;
    private final ApplicationContext domains;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate recoveryTemplate;
    private final Clock clock;
    private final ProductionArtifactService artifacts;
    private final ProductionNotificationService notifications;

    @Autowired
    public ProductionExecutionServiceImpl(HeronProductionTaskMapper taskMapper,
                                          HeronProductionLaneMapper laneMapper,
                                          HeronTaskStepMapper stepMapper,
                                          ApplicationContext domains,
                                          TransactionTemplate transactionTemplate,
                                          ProductionArtifactService artifacts,
                                          ProductionNotificationService notifications) {
        this(taskMapper, laneMapper, stepMapper, domains, transactionTemplate, Clock.systemUTC(), artifacts,
            notifications);
    }

    public ProductionExecutionServiceImpl(HeronProductionTaskMapper taskMapper,
                                          HeronProductionLaneMapper laneMapper,
                                          HeronTaskStepMapper stepMapper,
                                          ApplicationContext domains,
                                          TransactionTemplate transactionTemplate,
                                          Clock clock,
                                          ProductionArtifactService artifacts,
                                          ProductionNotificationService notifications) {
        this.taskMapper = taskMapper;
        this.laneMapper = laneMapper;
        this.stepMapper = stepMapper;
        this.domains = domains;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.artifacts = artifacts;
        this.notifications = notifications;
        this.recoveryTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.recoveryTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Optional<ProductionRunVo> claim(ProductionBizType type) {
        lockLane(type);
        if (taskMapper.countExecuting(type) > 0) {
            return Optional.empty();
        }
        HeronProductionTask candidate = taskMapper.selectQueuedHead(type);
        if (candidate == null) {
            return Optional.empty();
        }
        IProductionDomain domain = domain(type);
        domain.lockObject(candidate.getBizId());
        HeronProductionTask task = taskMapper.lockById(candidate.getTaskId());
        if (task == null || !ProductionStatus.queued.name().equals(task.getStatus())) {
            return Optional.empty();
        }

        LocalDateTime now = now();
        int attempt = task.getAttemptNo() + 1;
        String token = UUID.randomUUID().toString();
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .set(HeronProductionTask::getAttemptNo, attempt)
            .set(HeronProductionTask::getRunToken, token)
            .set(HeronProductionTask::getLeaseUntil, now.plusSeconds(LEASE_SECONDS))
            .set(HeronProductionTask::getHeartbeatAt, now)
            .set(HeronProductionTask::getStartedAt, now)
            .set(HeronProductionTask::getStage, null)
            .set(HeronProductionTask::getProgress, 0)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, task.getTaskId())
            .eq(HeronProductionTask::getStatus, ProductionStatus.queued.name())
            .updateCount();
        if (affected != 1) {
            return Optional.empty();
        }

        String checkpoint = null;
        if (attempt > 1) {
            HeronTaskStep previous = stepMapper.selectLatestCheckpoint(task.getTaskId(), attempt - 1);
            checkpoint = previous == null ? null : previous.getCheckpoint();
        }
        return Optional.of(new ProductionRunVo(task.getTaskId(), type, task.getBizId(), task.getVersionId(),
            attempt, token, task.getInputSnapshot(), checkpoint));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean heartbeat(long taskId, String runToken) {
        LocalDateTime now = now();
        return taskMapper.lambda()
            .set(HeronProductionTask::getLeaseUntil, now.plusSeconds(LEASE_SECONDS))
            .set(HeronProductionTask::getHeartbeatAt, now)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, taskId)
            .eq(HeronProductionTask::getRunToken, runToken)
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount() == 1;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean beginStage(ProductionRunVo run, String stage, int progress) {
        if (stage == null || stage.isBlank() || progress < 0 || progress > 99) {
            throw new HeronBusinessException(400, "production_invalid_stage", "阶段或进度不合法");
        }
        LocalDateTime now = now();
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStage, stage)
            .set(HeronProductionTask::getProgress, progress)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount();
        if (affected != 1) {
            return false;
        }
        if (stepMapper.selectStep(run.taskId(), run.attemptNo(), stage) != null) {
            throw new HeronBusinessException(409, "production_step_exists", "阶段已开始");
        }
        HeronTaskStep step = new HeronTaskStep();
        step.setStepId(IdUtil.getSnowflakeNextId());
        step.setTaskId(run.taskId());
        step.setAttemptNo(run.attemptNo());
        step.setStage(stage);
        step.setStatus(ProductionStatus.running.name());
        step.setStartedAt(now);
        stepMapper.insert(step);
        return true;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean checkpoint(ProductionRunVo run, String stage, String checkpointJson,
                              List<ProductionArtifactVo> evidence) {
        if (stage == null || stage.isBlank()) {
            throw new HeronBusinessException(400, "production_invalid_stage", "阶段不能为空");
        }
        LocalDateTime now = now();
        int guard = taskMapper.lambda()
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount();
        if (guard != 1) {
            return false;
        }
        String evidenceJson = evidence == null || evidence.isEmpty() ? null : JSON.writeValueAsString(evidence);
        int affected = stepMapper.lambda()
            .set(HeronTaskStep::getCheckpoint, checkpointJson)
            .set(evidenceJson != null, HeronTaskStep::getEvidence, evidenceJson)
            .set(HeronTaskStep::getUpdateTime, now)
            .eq(HeronTaskStep::getTaskId, run.taskId())
            .eq(HeronTaskStep::getAttemptNo, run.attemptNo())
            .eq(HeronTaskStep::getStage, stage)
            .eq(HeronTaskStep::getStatus, ProductionStatus.running.name())
            .updateCount();
        return affected == 1;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean succeed(ProductionRunVo run, ProductionResultVo result) {
        HeronProductionTask snapshot = taskMapper.selectById(run.taskId());
        if (snapshot == null || !snapshot.getBizType().equals(run.bizType().name())) {
            return false;
        }
        lockLane(run.bizType());
        IProductionDomain domain = domain(run.bizType());
        domain.lockObject(snapshot.getBizId());
        HeronProductionTask task = taskMapper.lockById(run.taskId());
        if (!ownedBy(task, run)) {
            return false;
        }
        if (result == null || result.artifacts() == null) {
            throw new HeronBusinessException(400, "production_invalid_result", "流水线结果不完整");
        }
        for (ProductionArtifactVo artifact : result.artifacts()) {
            artifacts.verify(artifact);
        }

        LocalDateTime now = now();
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, ProductionStatus.succeeded.name())
            .set(HeronProductionTask::getProgress, 100)
            .set(HeronProductionTask::getFinishedAt, now)
            .set(HeronProductionTask::getLeaseUntil, null)
            .set(HeronProductionTask::getErrorCode, null)
            .set(HeronProductionTask::getErrorReason, null)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount();
        if (affected != 1) {
            throw new HeronBusinessException(409, "production_state_changed", "执行归属或任务状态已变化");
        }
        domain.complete(task, result);
        finishRunningSteps(run, ProductionStatus.succeeded, now);
        domain.release(task);
        notifications.terminal(task.getTaskId(), run.bizType(), task.getActorId(), task.getOwnerId(),
            ProductionStatus.succeeded);
        return true;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean fail(ProductionRunVo run, ProductionFailureBo failure) {
        HeronProductionTask snapshot = taskMapper.selectById(run.taskId());
        if (snapshot == null || !snapshot.getBizType().equals(run.bizType().name())) {
            return false;
        }
        lockLane(run.bizType());
        IProductionDomain domain = domain(run.bizType());
        domain.lockObject(snapshot.getBizId());
        HeronProductionTask task = taskMapper.lockById(run.taskId());
        if (!ownedBy(task, run)) {
            return false;
        }

        LocalDateTime now = now();
        boolean externalUnknown = failure.externalOutcomeUnknown();
        String errorCode = externalUnknown ? "external_outcome_unknown" : failureCode(failure.code());
        String reason = truncate(externalUnknown ? "外部结果未知，已按失败处理" : failure.reason(), ERROR_REASON_LIMIT);
        String inputLocator = truncate(failure.inputLocator(), INPUT_LOCATOR_LIMIT);
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, ProductionStatus.failed.name())
            .set(HeronProductionTask::getErrorCode, errorCode)
            .set(HeronProductionTask::getErrorReason, reason)
            .set(HeronProductionTask::getFinishedAt, now)
            .set(HeronProductionTask::getLeaseUntil, null)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount();
        if (affected != 1) {
            throw new HeronBusinessException(409, "production_state_changed", "执行归属或任务状态已变化");
        }
        if (failure.stage() != null && !failure.stage().isBlank()) {
            stepMapper.lambda()
                .set(HeronTaskStep::getStatus, ProductionStatus.failed.name())
                .set(HeronTaskStep::getErrorCode, errorCode)
                .set(HeronTaskStep::getErrorReason, reason)
                .set(HeronTaskStep::getInputLocator, inputLocator)
                .set(HeronTaskStep::getFinishedAt, now)
                .set(HeronTaskStep::getUpdateTime, now)
                .eq(HeronTaskStep::getTaskId, run.taskId())
                .eq(HeronTaskStep::getAttemptNo, run.attemptNo())
                .eq(HeronTaskStep::getStage, failure.stage())
                .update();
        }
        finishRunningSteps(run, ProductionStatus.failed, now);
        domain.release(task);
        notifications.terminal(task.getTaskId(), run.bizType(), task.getActorId(), task.getOwnerId(),
            ProductionStatus.failed);
        return true;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean finishCancellation(ProductionRunVo run, boolean stopped, boolean externalUnknown) {
        HeronProductionTask snapshot = taskMapper.selectById(run.taskId());
        if (snapshot == null || !snapshot.getBizType().equals(run.bizType().name())) {
            return false;
        }
        lockLane(run.bizType());
        IProductionDomain domain = domain(run.bizType());
        domain.lockObject(snapshot.getBizId());
        HeronProductionTask task = taskMapper.lockById(run.taskId());
        if (task == null
            || !ProductionStatus.cancelling.name().equals(task.getStatus())
            || !run.runToken().equals(task.getRunToken())
            || task.getAttemptNo() != run.attemptNo()
            || !stopped) {
            return false;
        }

        boolean success = !externalUnknown;
        ProductionStatus terminal = success ? ProductionStatus.cancelled : ProductionStatus.failed;
        LocalDateTime now = now();
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, terminal.name())
            .set(HeronProductionTask::getErrorCode, success ? null : "external_outcome_unknown")
            .set(HeronProductionTask::getErrorReason, success ? null : "外部结果未知，已按失败处理")
            .set(HeronProductionTask::getFinishedAt, now)
            .set(HeronProductionTask::getLeaseUntil, null)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
            .updateCount();
        if (affected != 1) {
            throw new HeronBusinessException(409, "production_state_changed", "执行归属或任务状态已变化");
        }
        finishRunningSteps(run, terminal, now);
        domain.release(task);
        notifications.terminal(task.getTaskId(), run.bizType(), task.getActorId(), task.getOwnerId(), terminal);
        return true;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean requeueRecovered(ProductionRunVo run) {
        HeronProductionTask task = lockRecovered(run);
        if (task == null) {
            return false;
        }
        LocalDateTime now = now();
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, ProductionStatus.queued.name())
            .set(HeronProductionTask::getRunToken, null)
            .set(HeronProductionTask::getLeaseUntil, null)
            .set(HeronProductionTask::getHeartbeatAt, null)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
            .isNull(HeronProductionTask::getCancelRequestedAt)
            .updateCount();
        if (affected != 1) {
            return false;
        }
        interruptRunningSteps(run, now);
        return true;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean abandonRecovered(ProductionRunVo run, String errorCode, String errorReason) {
        HeronProductionTask task = lockRecovered(run);
        if (task == null) {
            return false;
        }
        LocalDateTime now = now();
        String code = failureCode(errorCode);
        String reason = truncate(errorReason, ERROR_REASON_LIMIT);
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, ProductionStatus.failed.name())
            .set(HeronProductionTask::getErrorCode, code)
            .set(HeronProductionTask::getErrorReason, reason)
            .set(HeronProductionTask::getFinishedAt, now)
            .set(HeronProductionTask::getLeaseUntil, null)
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, run.taskId())
            .eq(HeronProductionTask::getRunToken, run.runToken())
            .eq(HeronProductionTask::getAttemptNo, run.attemptNo())
            .eq(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
            .updateCount();
        if (affected != 1) {
            return false;
        }
        finishRunningSteps(run, ProductionStatus.failed, now);
        domain(run.bizType()).release(task);
        notifications.terminal(task.getTaskId(), run.bizType(), task.getActorId(), task.getOwnerId(),
            ProductionStatus.failed);
        return true;
    }

    private HeronProductionTask lockRecovered(ProductionRunVo run) {
        HeronProductionTask snapshot = taskMapper.selectById(run.taskId());
        if (snapshot == null || !snapshot.getBizType().equals(run.bizType().name())) {
            return null;
        }
        lockLane(run.bizType());
        domain(run.bizType()).lockObject(snapshot.getBizId());
        HeronProductionTask task = taskMapper.lockById(run.taskId());
        if (task == null
            || !ProductionStatus.cancelling.name().equals(task.getStatus())
            || !run.runToken().equals(task.getRunToken())
            || task.getAttemptNo() != run.attemptNo()) {
            return null;
        }
        return task;
    }

    private void interruptRunningSteps(ProductionRunVo run, LocalDateTime now) {
        stepMapper.lambda()
            .set(HeronTaskStep::getStatus, ProductionStatus.failed.name())
            .set(HeronTaskStep::getErrorCode, "recovery_interrupted")
            .set(HeronTaskStep::getErrorReason, "执行中断，已从检查点重排")
            .set(HeronTaskStep::getFinishedAt, now)
            .set(HeronTaskStep::getUpdateTime, now)
            .eq(HeronTaskStep::getTaskId, run.taskId())
            .eq(HeronTaskStep::getAttemptNo, run.attemptNo())
            .eq(HeronTaskStep::getStatus, ProductionStatus.running.name())
            .update();
    }

    @Override
    public void recoverExpired() {
        List<HeronProductionTask> expired = taskMapper.selectExpiredRunning(now(), RECOVERY_BATCH);
        for (HeronProductionTask task : expired) {
            try {
                recoveryTemplate.executeWithoutResult(status -> recoverOne(task.getTaskId()));
            } catch (RuntimeException e) {
                log.warn("回收过期任务失败，跳过继续扫描: taskId={}", task.getTaskId(), e);
            }
        }
    }

    private void recoverOne(long taskId) {
        HeronProductionTask snapshot = taskMapper.selectById(taskId);
        if (snapshot == null) {
            return;
        }
        ProductionBizType type = ProductionBizType.valueOf(snapshot.getBizType());
        lockLane(type);
        IProductionDomain domain = domain(type);
        domain.lockObject(snapshot.getBizId());
        HeronProductionTask task = taskMapper.lockById(taskId);
        LocalDateTime now = now();
        if (task == null
            || !ProductionStatus.running.name().equals(task.getStatus())
            || task.getLeaseUntil() == null
            || !task.getLeaseUntil().isBefore(now)) {
            return;
        }
        int affected = taskMapper.lambda()
            .set(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
            .set(HeronProductionTask::getRunToken, UUID.randomUUID().toString())
            .set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getTaskId, taskId)
            .eq(HeronProductionTask::getRunToken, task.getRunToken())
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount();
        if (affected != 1) {
            throw new HeronBusinessException(409, "production_state_changed", "执行归属或任务状态已变化");
        }
    }

    private boolean ownedBy(HeronProductionTask task, ProductionRunVo run) {
        return task != null
            && ProductionStatus.running.name().equals(task.getStatus())
            && run.runToken().equals(task.getRunToken())
            && task.getAttemptNo() == run.attemptNo();
    }

    private void finishRunningSteps(ProductionRunVo run, ProductionStatus status, LocalDateTime now) {
        stepMapper.lambda()
            .set(HeronTaskStep::getStatus, status.name())
            .set(HeronTaskStep::getFinishedAt, now)
            .set(HeronTaskStep::getUpdateTime, now)
            .eq(HeronTaskStep::getTaskId, run.taskId())
            .eq(HeronTaskStep::getAttemptNo, run.attemptNo())
            .eq(HeronTaskStep::getStatus, ProductionStatus.running.name())
            .update();
    }

    private void lockLane(ProductionBizType type) {
        if (laneMapper.lockByType(type) == null) {
            throw new IllegalStateException("生产线未初始化: " + type);
        }
    }

    private IProductionDomain domain(ProductionBizType type) {
        String beanName = type == ProductionBizType.news_video ? "newsProductionDomain" : "prototypeProductionDomain";
        try {
            return domains.getBean(beanName, IProductionDomain.class);
        } catch (org.springframework.beans.BeansException e) {
            throw new HeronBusinessException(503, "production_capability_missing", "生产线不可用: " + type);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String failureCode(String code) {
        if (code == null || code.isBlank()) {
            return "production_failed";
        }
        return truncate(code.strip(), ERROR_CODE_LIMIT);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
