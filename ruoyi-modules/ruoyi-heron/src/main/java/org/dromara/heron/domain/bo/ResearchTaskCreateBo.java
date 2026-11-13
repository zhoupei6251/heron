package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发起一次研究：目标机会 + 人写的判断要求。
 */
@Data
public class ResearchTaskCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "机会 id 不能为空")
    private Long opportunityId;

    /** 留空走默认目标：判断这条机会值不值得做成数据视频。 */
    private String goal;
}
