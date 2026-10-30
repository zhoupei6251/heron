package org.dromara.heron.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.page.PageQuery;
import java.util.HashMap;
import java.util.Map;

/** 作品分页筛选；对象范围始终取会话。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NewsWorkQuery extends PageQuery {
    private String title;
    private Long candidateId;
    private Map<String, Object> params = new HashMap<>();
}
