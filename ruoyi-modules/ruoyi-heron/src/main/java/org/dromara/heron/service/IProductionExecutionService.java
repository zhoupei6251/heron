package org.dromara.heron.service;

import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionFailureBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;

import java.util.List;
import java.util.Optional;

public interface IProductionExecutionService {

    Optional<ProductionRunVo> claim(ProductionBizType type);

    boolean heartbeat(long taskId, String runToken);

    boolean beginStage(ProductionRunVo run, String stage, int progress);

    boolean checkpoint(ProductionRunVo run, String stage, String checkpointJson,
                       List<ProductionArtifactVo> evidence);

    boolean succeed(ProductionRunVo run, ProductionResultVo result);

    boolean fail(ProductionRunVo run, ProductionFailureBo failure);

    boolean finishCancellation(ProductionRunVo run, boolean stopped, boolean externalUnknown);

    boolean requeueRecovered(ProductionRunVo run);

    boolean abandonRecovered(ProductionRunVo run, String errorCode, String errorReason);

    void recoverExpired();
}
