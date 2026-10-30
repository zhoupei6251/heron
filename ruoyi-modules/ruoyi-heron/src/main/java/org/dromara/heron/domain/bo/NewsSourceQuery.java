package org.dromara.heron.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.page.PageQuery;
import java.util.HashMap;
import java.util.Map;

/** 来源列表筛选。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NewsSourceQuery extends PageQuery {
    private String name;
    private String sourceType;
    private String enabled;
    private Map<String, Object> params = new HashMap<>();
}
