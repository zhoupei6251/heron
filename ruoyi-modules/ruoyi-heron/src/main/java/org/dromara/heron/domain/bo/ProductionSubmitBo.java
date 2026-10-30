package org.dromara.heron.domain.bo;

import org.dromara.heron.domain.ProductionBizType;

/**
 * 业务入口提交参数，画布等可变内容必须先落入草稿并递增 revision。
 */
public record ProductionSubmitBo(ProductionBizType bizType, long bizId,
                                 String requestKey, long expectedRevision) {
}
