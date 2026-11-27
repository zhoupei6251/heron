package org.dromara.heron.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.HashMap;
import java.util.Map;

/** 待发布池分页筛选；默认按优先分降序。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PublishInventoryQuery extends PageQuery {
    /** 评估状态缺省 eligible，空串返回全部。 */
    private String curationStatus;
    private String publishStatus;
    private String bizLine;
    private Long workId;
    private Map<String, Object> params = new HashMap<>();
}
