package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionRunVo;

/**
 * 稿件 JSON 由模型产出，渲染流水线只消费契约已过的外形，不关心模型如何被调用。
 */
public interface NewsScriptAuthor {

    String author(ProductionRunVo run);
}
