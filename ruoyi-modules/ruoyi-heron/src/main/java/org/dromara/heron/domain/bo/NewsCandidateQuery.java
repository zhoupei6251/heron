package org.dromara.heron.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.page.PageQuery;
import java.util.HashMap;
import java.util.Map;

/** 候选筛选；团队输入不接受 owner 覆盖参数。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NewsCandidateQuery extends PageQuery {
    private String title;
    private Boolean claimed;
    private Map<String, Object> params = new HashMap<>();
}
