package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.HeronTaskStep;
import org.dromara.heron.domain.HeronUsageRecord;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.bo.ProductionQueryBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionFileVo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionReservationVo;
import org.dromara.heron.domain.vo.ProductionStepVo;
import org.dromara.heron.domain.vo.ProductionTaskVo;
import org.dromara.heron.domain.vo.ProductionUsageVo;
import org.dromara.heron.mapper.HeronProductionLaneMapper;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronTaskStepMapper;
import org.dromara.heron.mapper.HeronUsageRecordMapper;
import org.dromara.heron.service.IProductionDomain;
import org.dromara.heron.service.IProductionService;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/**
 * 生产请求的准入、幂等与取消。
 */
@Service
public class ProductionServiceImpl implements IProductionService {

    public static final int PROTOTYPE_CAPACITY = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronProductionTaskMapper taskMapper;
    private final HeronProductionLaneMapper laneMapper;
    private final HeronTaskStepMapper stepMapper;
    private final HeronUsageRecordMapper usageMapper;
    private final ApplicationContext domains;
    private final HeronAccessService access;
    private final ProductionNotificationService notifications;

    @Autowired
    public ProductionServiceImpl(HeronProductionTaskMapper taskMapper,
                                 HeronProductionLaneMapper laneMapper,
                                 HeronTaskStepMapper stepMapper,
                                 HeronUsageRecordMapper usageMapper,
                                 ApplicationContext domains,
                                 HeronAccessService access,
                                 ProductionNotificationService notifications) {
        this.taskMapper = taskMapper;
        this.laneMapper = laneMapper;
        this.stepMapper = stepMapper;
        this.usageMapper = usageMapper;
        this.domains = domains;
        this.access = access;
        this.notifications = notifications;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductionReceiptVo submit(ProductionSubmitBo command) {
        validate(command);
        long actorId = access.requireActor();
        ProductionBizType type = command.bizType();
        String requestHash = sha256(type.name() + "\n" + command.bizId() + "\n" + command.expectedRevision());

        lockLane(type);
        HeronProductionTask existing = taskMapper.selectByRequest(actorId, type, command.bizId(), command.requestKey());
        if (existing != null) {
            access.requireObject(existing.getOwnerId(), type, HeronAccessService.generatePermission(type));
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new org.dromara.heron.domain.HeronBusinessException(409, "production_request_conflict",
                    "同一请求键对应不同输入");
            }
            return receipt(existing);
        }

        if (type == ProductionBizType.prototype && taskMapper.countAdmitted(type) >= PROTOTYPE_CAPACITY) {
            throw new org.dromara.heron.domain.HeronBusinessException(409, "production_capacity_full",
                "原型生产线已满 3 个在途任务");
        }

        long taskId = IdUtil.getSnowflakeNextId();
        IProductionDomain domain = domain(type);
        if (!(domain instanceof org.dromara.heron.service.IProductionObjectScope scope)) {
            throw new org.dromara.heron.domain.HeronBusinessException(503, "production_capability_missing",
                "生产线缺少对象归属校验: " + type);
        }
        domain.lockObject(command.bizId());
        access.requireObject(scope.ownerOf(command.bizId()), type, HeronAccessService.generatePermission(type));
        ProductionReservationVo reservation = domain.reserve(command, actorId, taskId);
        access.requireObject(reservation.ownerId(), type, HeronAccessService.generatePermission(type));

        HeronProductionTask task = new HeronProductionTask();
        task.setTaskId(taskId);
        task.setBizType(type.name());
        task.setBizId(command.bizId());
        task.setVersionId(reservation.versionId());
        task.setOwnerId(reservation.ownerId());
        task.setActorId(actorId);
        task.setRequestKey(command.requestKey());
        task.setRequestHash(requestHash);
        task.setInputHash(sha256(reservation.inputJson()));
        task.setInputSnapshot(reservation.inputJson());
        task.setStatus(ProductionStatus.queued.name());
        task.setProgress(0);
        task.setAttemptNo(0);
        if (taskMapper.insert(task) != 1) {
            throw new IllegalStateException("生产任务写入失败");
        }
        return receipt(task);
    }

    @Override
    public ProductionTaskVo queryById(long taskId) {
        HeronProductionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new HeronAccessException();
        }
        ProductionBizType type = ProductionBizType.valueOf(task.getBizType());
        access.requireObject(task.getOwnerId(), type, "heron:production:query");
        ProductionTaskVo vo = toVo(task);
        vo.setSteps(stepMapper.selectList(QueryBuilder.lambda(HeronTaskStep.class)
                .eq(HeronTaskStep::getTaskId, taskId)
                .orderByAsc(HeronTaskStep::getStartedAt)
                .build())
            .stream().map(this::toStepVo).toList());
        vo.setUsage(usageMapper.selectList(QueryBuilder.lambda(HeronUsageRecord.class)
                .eq(HeronUsageRecord::getTaskId, taskId)
                .orderByAsc(HeronUsageRecord::getCreateTime)
                .build())
            .stream().map(this::toUsageVo).toList());
        vo.setArtifacts(List.of());
        return vo;
    }

    @Override
    public PageResult<ProductionTaskVo> queryPageList(ProductionQueryBo query, PageQuery pageQuery) {
        Long actor = access.requireActor();
        LambdaQueryWrapper<HeronProductionTask> wrapper = QueryBuilder.lambda(HeronProductionTask.class)
            .eq(query.getBizType() != null, HeronProductionTask::getBizType,
                query.getBizType() == null ? null : query.getBizType().name())
            .eq(query.getStatus() != null && !query.getStatus().isBlank(), HeronProductionTask::getStatus, query.getStatus())
            .eq(query.getBizId() != null, HeronProductionTask::getBizId, query.getBizId())
            .orderByDesc(HeronProductionTask::getCreateTime, HeronProductionTask::getTaskId)
            .build();
        if (!LoginHelper.isSuperAdmin(actor)) {
            boolean allNews = access.hasAllScope(ProductionBizType.news_video);
            boolean allProto = access.hasAllScope(ProductionBizType.prototype);
            if (!allNews || !allProto) {
                wrapper.and(scope -> {
                    scope.eq(HeronProductionTask::getOwnerId, actor);
                    if (allNews) {
                        scope.or().eq(HeronProductionTask::getBizType, ProductionBizType.news_video.name());
                    }
                    if (allProto) {
                        scope.or().eq(HeronProductionTask::getBizType, ProductionBizType.prototype.name());
                    }
                });
            }
        }
        Page<HeronProductionTask> page = taskMapper.selectPage(pageQuery.build(), wrapper);
        List<ProductionTaskVo> rows = page.getRecords().stream().map(this::toVo).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductionTaskVo cancel(long taskId) {
        HeronProductionTask snapshot = taskMapper.selectById(taskId);
        if (snapshot == null) {
            throw new HeronAccessException();
        }
        ProductionBizType type = ProductionBizType.valueOf(snapshot.getBizType());
        access.requireObject(snapshot.getOwnerId(), type, "heron:production:cancel");
        if (!access.isSelfOrAdmin(snapshot.getActorId())) {
            access.requirePermission(HeronAccessService.generatePermission(type));
        }

        lockLane(type);
        IProductionDomain domain = domain(type);
        domain.lockObject(snapshot.getBizId());
        HeronProductionTask task = taskMapper.lockById(taskId);
        if (task == null) {
            throw new HeronAccessException();
        }
        LocalDateTime now = now();
        if (ProductionStatus.queued.name().equals(task.getStatus())) {
            int affected = taskMapper.lambda()
                .set(HeronProductionTask::getStatus, ProductionStatus.cancelled.name())
                .set(HeronProductionTask::getFinishedAt, now)
                .set(HeronProductionTask::getUpdateTime, now)
                .eq(HeronProductionTask::getTaskId, taskId)
                .eq(HeronProductionTask::getStatus, ProductionStatus.queued.name())
                .updateCount();
            if (affected != 1) {
                throw new org.dromara.heron.domain.HeronBusinessException(409, "production_state_changed",
                    "任务状态已变化");
            }
            domain.release(task);
            notifications.terminal(task.getTaskId(), type, task.getActorId(), task.getOwnerId(),
                ProductionStatus.cancelled);
        } else if (ProductionStatus.running.name().equals(task.getStatus())) {
            int affected = taskMapper.lambda()
                .set(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
                .set(HeronProductionTask::getCancelRequestedAt, now)
                .set(HeronProductionTask::getUpdateTime, now)
                .eq(HeronProductionTask::getTaskId, taskId)
                .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
                .updateCount();
            if (affected != 1) {
                throw new org.dromara.heron.domain.HeronBusinessException(409, "production_state_changed",
                    "任务状态已变化");
            }
        } else if (ProductionStatus.cancelling.name().equals(task.getStatus())
            && task.getCancelRequestedAt() == null) {
            taskMapper.lambda()
                .set(HeronProductionTask::getCancelRequestedAt, now)
                .set(HeronProductionTask::getUpdateTime, now)
                .eq(HeronProductionTask::getTaskId, taskId)
                .eq(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
                .isNull(HeronProductionTask::getCancelRequestedAt)
                .update();
        }
        return toVo(taskMapper.selectById(taskId));
    }

    private void validate(ProductionSubmitBo command) {
        if (command == null || command.bizType() == null || command.bizId() <= 0
            || command.requestKey() == null || command.requestKey().isBlank()
            || command.requestKey().length() > 64 || command.expectedRevision() < 0) {
            throw new org.dromara.heron.domain.HeronBusinessException(400, "production_invalid_input",
                "请求参数不合法");
        }
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
            throw new org.dromara.heron.domain.HeronBusinessException(503, "production_capability_missing",
                "生产线不可用: " + type);
        }
    }

    private ProductionReceiptVo receipt(HeronProductionTask task) {
        return new ProductionReceiptVo(task.getTaskId(), ProductionBizType.valueOf(task.getBizType()),
            task.getBizId(), task.getVersionId(), ProductionStatus.valueOf(task.getStatus()));
    }

    private ProductionTaskVo toVo(HeronProductionTask task) {
        ProductionTaskVo vo = new ProductionTaskVo();
        vo.setTaskId(task.getTaskId());
        vo.setBizType(ProductionBizType.valueOf(task.getBizType()));
        vo.setBizId(task.getBizId());
        vo.setVersionId(task.getVersionId());
        vo.setStatus(ProductionStatus.valueOf(task.getStatus()));
        vo.setStage(task.getStage());
        vo.setProgress(task.getProgress());
        vo.setAttemptNo(task.getAttemptNo());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setErrorCode(task.getErrorCode());
        vo.setErrorReason(task.getErrorReason());
        return vo;
    }

    private ProductionStepVo toStepVo(HeronTaskStep step) {
        ProductionStepVo vo = new ProductionStepVo();
        vo.setStage(step.getStage());
        vo.setStatus(step.getStatus());
        vo.setStartedAt(step.getStartedAt());
        vo.setFinishedAt(step.getFinishedAt());
        vo.setErrorCode(step.getErrorCode());
        vo.setErrorReason(step.getErrorReason());
        vo.setEvidence(parseEvidence(step.getEvidence()));
        return vo;
    }

    private List<ProductionFileVo> parseEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return List.of();
        }
        ProductionArtifactVo[] artifacts = JSON.readValue(evidence, ProductionArtifactVo[].class);
        return java.util.Arrays.stream(artifacts)
            .map(a -> new ProductionFileVo(a.artifactId(), a.kind(), a.bytes(), a.sha256(), a.mediaType(), true))
            .toList();
    }

    private ProductionUsageVo toUsageVo(HeronUsageRecord record) {
        ProductionUsageVo vo = new ProductionUsageVo();
        vo.setProvider(record.getProvider());
        vo.setModel(record.getModel());
        vo.setStatus(record.getStatus());
        vo.setInputTokens(record.getInputTokens());
        vo.setOutputTokens(record.getOutputTokens());
        vo.setElapsedMs(record.getElapsedMs());
        return vo;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
