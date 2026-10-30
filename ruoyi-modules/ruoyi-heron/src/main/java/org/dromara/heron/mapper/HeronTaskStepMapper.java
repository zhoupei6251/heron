package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronTaskStep;
import org.dromara.heron.domain.vo.ProductionStepVo;

public interface HeronTaskStepMapper extends BaseMapperPlus<HeronTaskStep, ProductionStepVo> {

    default HeronTaskStep selectStep(long taskId, int attemptNo, String stage) {
        return selectOne(QueryBuilder.lambda(HeronTaskStep.class)
            .eq(HeronTaskStep::getTaskId, taskId)
            .eq(HeronTaskStep::getAttemptNo, attemptNo)
            .eq(HeronTaskStep::getStage, stage)
            .build());
    }

    default HeronTaskStep selectLatestCheckpoint(long taskId, int attemptNo) {
        return selectOne(QueryBuilder.lambda(HeronTaskStep.class)
            .eq(HeronTaskStep::getTaskId, taskId)
            .eq(HeronTaskStep::getAttemptNo, attemptNo)
            .isNotNull(HeronTaskStep::getCheckpoint)
            .orderByDesc(HeronTaskStep::getStartedAt)
            .last("LIMIT 1")
            .build());
    }

    default HeronTaskStep selectLatestIdentity(long taskId, int attemptNo) {
        return selectOne(QueryBuilder.lambda(HeronTaskStep.class)
            .eq(HeronTaskStep::getTaskId, taskId)
            .eq(HeronTaskStep::getAttemptNo, attemptNo)
            .isNotNull(HeronTaskStep::getRuntimeIdentity)
            .orderByDesc(HeronTaskStep::getStartedAt)
            .last("LIMIT 1")
            .build());
    }
}
