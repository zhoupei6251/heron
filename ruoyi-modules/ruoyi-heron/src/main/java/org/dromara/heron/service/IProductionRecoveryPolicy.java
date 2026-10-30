package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionRunVo;

/**
 * 流水线声明崩溃后能否从检查点继续；未实现或不确认时恢复只终结、不重排。
 */
public interface IProductionRecoveryPolicy {

    boolean canResume(ProductionRunVo run);
}
