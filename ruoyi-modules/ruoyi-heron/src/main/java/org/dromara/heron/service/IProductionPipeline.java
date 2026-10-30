package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;

/**
 * 固定流水线执行入口，不通过 HTTP 暴露。
 */
public interface IProductionPipeline {

    ProductionResultVo execute(ProductionRunVo run);
}
