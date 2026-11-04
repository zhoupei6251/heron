package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionRunVo;

/**
 * 流水线回报阶段的唯一入口。执行核心的其余能力（领取、心跳、成败）不归流水线，
 * 收成单方法接口也是为了让流水线能在测试里直接构造。
 */
@FunctionalInterface
public interface ProductionStageReporter {

    boolean beginStage(ProductionRunVo run, String stage, int progress);
}
