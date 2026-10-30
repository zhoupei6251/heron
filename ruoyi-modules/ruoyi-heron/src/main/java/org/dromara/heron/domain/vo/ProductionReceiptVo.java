package org.dromara.heron.domain.vo;

import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;

/**
 * 提交后返回的任务凭据，同键重放返回原任务。
 */
public record ProductionReceiptVo(long taskId, ProductionBizType bizType,
                                  long bizId, long versionId, ProductionStatus status) {
}
