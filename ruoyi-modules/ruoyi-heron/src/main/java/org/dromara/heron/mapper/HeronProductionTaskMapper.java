package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.vo.ProductionTaskVo;

import java.time.LocalDateTime;
import java.util.List;

public interface HeronProductionTaskMapper extends BaseMapperPlus<HeronProductionTask, ProductionTaskVo> {

    default HeronProductionTask lockById(long taskId) {
        return selectOne(QueryBuilder.lambda(HeronProductionTask.class)
            .eq(HeronProductionTask::getTaskId, taskId)
            .last("FOR UPDATE")
            .build());
    }

    default HeronProductionTask selectByRequest(long actorId, ProductionBizType bizType,
                                                long bizId, String requestKey) {
        return selectOne(QueryBuilder.lambda(HeronProductionTask.class)
            .eq(HeronProductionTask::getActorId, actorId)
            .eq(HeronProductionTask::getBizType, bizType.name())
            .eq(HeronProductionTask::getBizId, bizId)
            .eq(HeronProductionTask::getRequestKey, requestKey)
            .build());
    }

    default HeronProductionTask selectQueuedHead(ProductionBizType bizType) {
        return selectOne(QueryBuilder.lambda(HeronProductionTask.class)
            .eq(HeronProductionTask::getBizType, bizType.name())
            .eq(HeronProductionTask::getStatus, ProductionStatus.queued.name())
            .orderByAsc(HeronProductionTask::getCreateTime, HeronProductionTask::getTaskId)
            .last("LIMIT 1")
            .build());
    }

    default long countAdmitted(ProductionBizType bizType) {
        return lambda()
            .eq(HeronProductionTask::getBizType, bizType.name())
            .in(HeronProductionTask::getStatus,
                ProductionStatus.queued.name(),
                ProductionStatus.running.name(),
                ProductionStatus.cancelling.name())
            .count();
    }

    default long countExecuting(ProductionBizType bizType) {
        return lambda()
            .eq(HeronProductionTask::getBizType, bizType.name())
            .in(HeronProductionTask::getStatus,
                ProductionStatus.running.name(),
                ProductionStatus.cancelling.name())
            .count();
    }

    default List<HeronProductionTask> selectExpiredRunning(LocalDateTime now, int limit) {
        return selectList(QueryBuilder.lambda(HeronProductionTask.class)
            .eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .lt(HeronProductionTask::getLeaseUntil, now)
            .orderByAsc(HeronProductionTask::getLeaseUntil)
            .last("LIMIT " + limit)
            .build());
    }

    default List<HeronProductionTask> selectExpiredCancelling(LocalDateTime now, int limit) {
        return selectList(QueryBuilder.lambda(HeronProductionTask.class)
            .eq(HeronProductionTask::getStatus, ProductionStatus.cancelling.name())
            .lt(HeronProductionTask::getLeaseUntil, now)
            .orderByAsc(HeronProductionTask::getLeaseUntil)
            .last("LIMIT " + limit)
            .build());
    }
}
