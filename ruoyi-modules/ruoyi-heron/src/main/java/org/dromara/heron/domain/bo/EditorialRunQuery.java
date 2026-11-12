package org.dromara.heron.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.time.LocalDate;

/** 编辑运行分页筛选。运行记录只读，不存在写入入口。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EditorialRunQuery extends PageQuery {
    /** produced / skipped / failed。 */
    private String status;
    /** DATA_BOARD / NEWS_EXPLAINER / NO_PUBLISH。 */
    private String selectedLine;
    private LocalDate beginDate;
    private LocalDate endDate;
}
