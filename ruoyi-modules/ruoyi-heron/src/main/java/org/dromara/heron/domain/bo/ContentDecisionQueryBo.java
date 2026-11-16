package org.dromara.heron.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 内容决策查询条件。
 */
@Data
public class ContentDecisionQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long opportunityId;

    private String decisionType;

    private String status;

    private Map<String, Object> params;
}
