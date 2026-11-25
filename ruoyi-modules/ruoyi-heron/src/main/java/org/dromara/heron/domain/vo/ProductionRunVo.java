package org.dromara.heron.domain.vo;

import org.dromara.heron.domain.ProductionBizType;

/**
 * 领取后的执行上下文，runToken 只供持有者写入。
 *
 * <p>不带阶段续跑信息：{@code checkpoint} 曾经写进阶段行，但生产链路从来没有读者，
 * 已经按计划删除（列保留，历史数据仍可查）。真要做阶段级续跑，先看渲染失败分布再单独立项。
 */
public record ProductionRunVo(long taskId, ProductionBizType bizType, long bizId,
                              long versionId, int attemptNo, String runToken,
                              String inputJson) {
}
