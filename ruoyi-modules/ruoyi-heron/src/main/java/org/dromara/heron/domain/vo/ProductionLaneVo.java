package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronProductionLane;
import org.dromara.heron.domain.ProductionBizType;

import java.io.Serial;
import java.io.Serializable;

/**
 * 容量锁行视图，不单独开放接口。
 */
@Data
@AutoMapper(target = HeronProductionLane.class)
public class ProductionLaneVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private ProductionBizType bizType;
}
