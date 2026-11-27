package org.dromara.heron.service.impl.shared.production;

import cn.hutool.core.util.IdUtil;
import org.dromara.heron.domain.HeronUsageRecord;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronUsageRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 外部调用账本：同一 callId 最多完成一次，未知用量保持 null。
 */
@Service
public class ProductionUsageService {

    public static final String STARTED = "started";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String UNKNOWN = "unknown";

    private static final int CALL_ID_LIMIT = 36;
    private static final int STAGE_LIMIT = 32;
    private static final int PROVIDER_LIMIT = 80;
    private static final int MODEL_LIMIT = 160;
    private static final int PROVIDER_CALL_ID_LIMIT = 160;
    private static final int ERROR_CODE_LIMIT = 64;

    private final HeronUsageRecordMapper usageMapper;

    public ProductionUsageService(HeronUsageRecordMapper usageMapper) {
        this.usageMapper = usageMapper;
    }

    /**
     * 登记一次外部调用的开始；callId 在任务+尝试内唯一。
     *
     * @return true 表示新登记成功；false 表示同一 callId 已存在（重放或并发）
     * @throws IllegalArgumentException 参数缺失或超长
     */
    public boolean start(ProductionRunVo run, String stage, String callId, String provider, String model) {
        if (run == null || blank(callId) || callId.length() > CALL_ID_LIMIT
            || blank(stage) || stage.length() > STAGE_LIMIT
            || tooLong(provider, PROVIDER_LIMIT) || tooLong(model, MODEL_LIMIT)) {
            throw new IllegalArgumentException("用量记录参数不合法");
        }
        HeronUsageRecord record = new HeronUsageRecord();
        record.setId(IdUtil.getSnowflakeNextId());
        record.setTaskId(run.taskId());
        record.setAttemptNo(run.attemptNo());
        record.setCallId(callId);
        record.setStage(stage);
        record.setProvider(provider);
        record.setModel(model);
        record.setStatus(STARTED);
        try {
            return usageMapper.insert(record) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 把已开始的调用标记为成功，可空字段只在非空时覆盖。
     *
     * @return true 表示正好更新了一条仍处于 started 且任务仍在 running 的记录
     * @throws IllegalArgumentException 参数不合法
     */
    public boolean complete(ProductionRunVo run, String callId, String providerCallId,
                            Long inputTokens, Long outputTokens, long elapsedMs) {
        if (run == null || blank(callId) || elapsedMs < 0 || tooLong(providerCallId, PROVIDER_CALL_ID_LIMIT)) {
            throw new IllegalArgumentException("用量完成参数不合法");
        }
        return usageMapper.lambda()
            .set(HeronUsageRecord::getStatus, SUCCEEDED)
            .set(providerCallId != null && !providerCallId.isBlank(),
                HeronUsageRecord::getProviderCallId, providerCallId)
            .set(inputTokens != null, HeronUsageRecord::getInputTokens, inputTokens)
            .set(outputTokens != null, HeronUsageRecord::getOutputTokens, outputTokens)
            .set(HeronUsageRecord::getElapsedMs, elapsedMs)
            .eq(HeronUsageRecord::getTaskId, run.taskId())
            .eq(HeronUsageRecord::getAttemptNo, run.attemptNo())
            .eq(HeronUsageRecord::getCallId, callId)
            .eq(HeronUsageRecord::getStatus, STARTED)
            .exists("SELECT 1 FROM heron_production_task WHERE id = {0} AND run_token = {1} "
                + "AND attempt_no = {2} AND status = 'running'",
                run.taskId(), run.runToken(), run.attemptNo())
            .updateCount() == 1;
    }

    /**
     * 把已开始的调用标记为失败，并记录错误码与耗时。
     *
     * @return true 表示正好更新了一条仍处于 started 且任务仍在 running 的记录
     * @throws IllegalArgumentException 参数不合法
     */
    public boolean fail(ProductionRunVo run, String callId, String errorCode, long elapsedMs) {
        if (run == null || blank(callId) || tooLong(errorCode, ERROR_CODE_LIMIT)) {
            throw new IllegalArgumentException("用量失败参数不合法");
        }
        return usageMapper.lambda()
            .set(HeronUsageRecord::getStatus, FAILED)
            .set(errorCode != null && !errorCode.isBlank(), HeronUsageRecord::getErrorCode, errorCode)
            .set(HeronUsageRecord::getElapsedMs, Math.max(elapsedMs, 0))
            .eq(HeronUsageRecord::getTaskId, run.taskId())
            .eq(HeronUsageRecord::getAttemptNo, run.attemptNo())
            .eq(HeronUsageRecord::getCallId, callId)
            .eq(HeronUsageRecord::getStatus, STARTED)
            .exists("SELECT 1 FROM heron_production_task WHERE id = {0} AND run_token = {1} "
                + "AND attempt_no = {2} AND status = 'running'",
                run.taskId(), run.runToken(), run.attemptNo())
            .updateCount() == 1;
    }

    /** 任务进入 cancelling 时，把本次尝试所有 started 调用标成用量未知，返回更新条数。 */
    public int markAttemptUnknown(ProductionRunVo run) {
        return markAttemptUnknown(run, "cancelling");
    }

    /** 流水线失败时，把本次尝试所有 started 调用标成用量未知，返回更新条数。 */
    public int markAttemptUnknownOnFailure(ProductionRunVo run) {
        return markAttemptUnknown(run, "running");
    }

    private int markAttemptUnknown(ProductionRunVo run, String taskStatus) {
        return usageMapper.lambda()
            .set(HeronUsageRecord::getStatus, UNKNOWN)
            .eq(HeronUsageRecord::getTaskId, run.taskId())
            .eq(HeronUsageRecord::getAttemptNo, run.attemptNo())
            .eq(HeronUsageRecord::getStatus, STARTED)
            .exists("SELECT 1 FROM heron_production_task WHERE id = {0} AND run_token = {1} "
                + "AND attempt_no = {2} AND status = {3}",
                run.taskId(), run.runToken(), run.attemptNo(), taskStatus)
            .updateCount();
    }

    /** 本次尝试仍处于 started 的调用数：大于 0 说明外部结果可能未知。 */
    public long startedCount(long taskId, int attemptNo) {
        return usageMapper.lambda()
            .eq(HeronUsageRecord::getTaskId, taskId)
            .eq(HeronUsageRecord::getAttemptNo, attemptNo)
            .eq(HeronUsageRecord::getStatus, STARTED)
            .count();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int limit) {
        return value != null && value.length() > limit;
    }
}
