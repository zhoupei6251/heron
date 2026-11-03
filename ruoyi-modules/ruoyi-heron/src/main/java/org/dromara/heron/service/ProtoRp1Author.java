package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionRunVo;

/**
 * RP/1 源码由模型产出，装配流水线只消费文本，不关心模型如何被调用。
 */
public interface ProtoRp1Author {

    String author(ProductionRunVo run);
}
