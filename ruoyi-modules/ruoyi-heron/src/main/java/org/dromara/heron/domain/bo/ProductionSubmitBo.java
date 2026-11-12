package org.dromara.heron.domain.bo;

import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.ProductionBizType;

/**
 * 业务入口提交参数，画布等可变内容必须先落入草稿并递增 revision。
 *
 * <p>editorial / selection 只在自动线出现：它们是线内编辑的产物，必须在预留版本时
 * 一次性冻结进快照。手工认领没有这两项，两段都为 null。
 */
public record ProductionSubmitBo(ProductionBizType bizType, long bizId,
                                 String requestKey, long expectedRevision,
                                 org.dromara.heron.service.impl.EditorialDecisionService.Decision editorial,
                                 EditorialSelection selection) {

    /** 手工/原型路径：没有编辑决策。 */
    public ProductionSubmitBo(ProductionBizType bizType, long bizId, String requestKey, long expectedRevision) {
        this(bizType, bizId, requestKey, expectedRevision, null, null);
    }
}
