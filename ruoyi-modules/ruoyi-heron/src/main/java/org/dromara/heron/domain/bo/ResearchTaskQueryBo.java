package org.dromara.heron.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 研究任务查询条件。
 */
@Data
public class ResearchTaskQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long opportunityId;

    private String status;

    private String decisionType;

    /** 预留：起止时间等扩展条件。 */
    private Map<String, Object> params;
}
