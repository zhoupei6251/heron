package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronProductionLane;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionLaneVo;

public interface HeronProductionLaneMapper extends BaseMapperPlus<HeronProductionLane, ProductionLaneVo> {

    default HeronProductionLane lockByType(ProductionBizType bizType) {
        return selectOne(QueryBuilder.lambda(HeronProductionLane.class)
            .eq(HeronProductionLane::getBizType, bizType.name())
            .last("FOR UPDATE")
            .build());
    }
}
