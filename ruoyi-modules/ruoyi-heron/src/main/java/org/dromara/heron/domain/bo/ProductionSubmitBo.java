package org.dromara.heron.domain.bo;

import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.ProductionBizType;

/**
 * 业务入口提交参数，画布等可变内容必须先落入草稿并递增 revision。
 *
 * <p>editorial / selection 只在自动线出现：它们是线内编辑的产物，必须在预留版本时
 * 一次性冻结进快照。手工认领没有这两项，两段都为 null。
 *
 * <p>override 是人工编辑策划结论时的沿用件：沿用上一版冻结快照里的 selection / editorial 节点，
 * 只换 story 一个键。有 override 时 editorial / selection 不再使用。
 */
public record ProductionSubmitBo(ProductionBizType bizType, long bizId,
                                 String requestKey, long expectedRevision,
                                 org.dromara.heron.service.impl.EditorialDecisionService.Decision editorial,
                                 EditorialSelection selection,
                                 org.dromara.heron.domain.EditorialOverride override) {

    /** 手工/原型路径：没有编辑决策。 */
    public ProductionSubmitBo(ProductionBizType bizType, long bizId, String requestKey, long expectedRevision) {
        this(bizType, bizId, requestKey, expectedRevision, null, null, null);
    }

    /** 自动线路径：带编辑决策与线内选择。 */
    public ProductionSubmitBo(ProductionBizType bizType, long bizId, String requestKey, long expectedRevision,
                              org.dromara.heron.service.impl.EditorialDecisionService.Decision editorial,
                              EditorialSelection selection) {
        this(bizType, bizId, requestKey, expectedRevision, editorial, selection, null);
    }
}
