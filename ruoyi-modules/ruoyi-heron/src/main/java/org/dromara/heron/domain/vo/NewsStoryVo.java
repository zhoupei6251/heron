package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 一条片子的策划结论与可编辑范围。
 *
 * <p>枚举在 Java 侧（{@code DatasetStoryContract}），页面按它渲染下拉框：
 * 枚举散到前端就会各写一份，策划结论的合法取值就再没有唯一出处。
 */
@Data
public class NewsStoryVo implements Serializable {
    private Long workId;
    private Long versionId;
    private Integer versionNo;
    /** 生产线：只有数据看板线与趋势洞察线带策划结论。 */
    private String line;
    private String dataSourceKey;
    /** 这次片子用的取数点数；编辑校验按同一条口径取数。 */
    private Integer points;
    /** 策划结论 story/1，原样返回给页面。 */
    private Map<String, Object> story;
    /** 可选叙事类型。 */
    private List<String> narratives;
    /** 叙事类型与张力类型的相容关系。 */
    private Map<String, List<String>> tensionKinds;
    /** 主角类型：metric / entity / group。 */
    private List<String> protagonistKinds;
    /** 主张类型：fact / assumption。 */
    private List<String> claimKinds;
}
