package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionRunVo;

import java.util.List;

/**
 * 稿件 JSON 由模型产出，渲染流水线只消费契约已过的外形，不关心模型如何被调用。
 */
public interface NewsScriptAuthor {

    String author(ProductionRunVo run);

    /** 审稿判 BLOCKER/MAJOR 后的一次定向修复；只改被点出的问题。 */
    String repair(ProductionRunVo run, String scriptJson, List<String> notes);
}
