package org.dromara.heron.domain.bo;

import lombok.Data;
import org.dromara.heron.domain.ProductionBizType;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 任务中心查询条件，权限范围不由此对象覆盖。
 */
@Data
public class ProductionQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private ProductionBizType bizType;

    private String status;

    private Long bizId;

    private Map<String, Object> params;
}
