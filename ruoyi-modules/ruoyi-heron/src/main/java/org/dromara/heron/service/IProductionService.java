package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.bo.ProductionQueryBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionTaskVo;

public interface IProductionService {

    ProductionReceiptVo submit(ProductionSubmitBo command);

    ProductionTaskVo queryById(long taskId);

    PageResult<ProductionTaskVo> queryPageList(ProductionQueryBo query, PageQuery page);

    ProductionTaskVo cancel(long taskId);
}
