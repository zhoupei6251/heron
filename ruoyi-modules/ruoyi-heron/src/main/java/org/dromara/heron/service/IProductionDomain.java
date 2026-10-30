package org.dromara.heron.service;

import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReservationVo;
import org.dromara.heron.domain.vo.ProductionResultVo;

/**
 * 固定业务线的预留与提交接缝，实现只依赖自身 mapper 和快照校验。
 */
public interface IProductionDomain {

    void lockObject(long bizId);

    ProductionReservationVo reserve(ProductionSubmitBo command, long actorId, long taskId);

    void complete(HeronProductionTask task, ProductionResultVo result);

    void release(HeronProductionTask task);
}
