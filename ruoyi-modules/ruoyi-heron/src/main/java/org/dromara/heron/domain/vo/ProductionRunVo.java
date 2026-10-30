package org.dromara.heron.domain.vo;

import org.dromara.heron.domain.ProductionBizType;

/**
 * 领取后的执行上下文，runToken 只供持有者写入。
 */
public record ProductionRunVo(long taskId, ProductionBizType bizType, long bizId,
                              long versionId, int attemptNo, String runToken,
                              String inputJson, String resumeCheckpointJson) {
}
