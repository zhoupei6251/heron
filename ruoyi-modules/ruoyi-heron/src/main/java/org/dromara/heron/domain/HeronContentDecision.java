package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次研究给出的判断，以及人对它的处理。
 *
 * <p>为什么不只读 {@code heron_research_task.report_json}：研究是可重跑的，
 * 重跑会覆盖同一行的报告；而决策一旦产生就是历史事实——重跑不该改写「当时人看过什么、怎么定的」。
 * 所以这里存决策那一刻的快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_content_decision")
public class HeronContentDecision extends BaseEntity {

    /** 待处理：Agent 已经给出判断，人还没看。 */
    public static final String STATUS_PROPOSED = "proposed";
    /** 采纳：人认可这个判断。 */
    public static final String STATUS_ACCEPTED = "accepted";
    /** 人工否决：人不同意。这是 plan.md §7.1 里「最终是否允许生产仍由业务规则校验」的落点。 */
    public static final String STATUS_REJECTED = "rejected";

    /** 建议生产：唯一值得再花一次核验的判断，也是唯一能提升为候选的判断。 */
    public static final String DECISION_PRODUCE = "PRODUCE";
    /** 核验员与研究员结论冲突：默认阻断提升，人要推翻必须留理由。 */
    public static final String VERDICT_DISAGREE = "DISAGREE";

    @TableId private Long id;
    private Long researchId;
    private Integer attemptNo;
    private Long opportunityId;
    private String decisionType;
    private BigDecimal confidence;
    private String decisionReason;
    /** 报告里的 data_sha256；没有绑定快照时为空。 */
    private String evidenceRef;
    private String reportVersion;
    private String promptVersion;
    private String modelId;
    private String status;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String decisionNote;
    /** 独立核验员的判定；未核验时为 null。 */
    private String verificationVerdict;
    private String verificationReason;
    /** 核验员指出的具体冲突，JSON 数组文本。 */
    private String verificationConflicts;
    /**
     * 人工覆盖核验冲突的理由。
     *
     * <p>核验判 DISAGREE 时提升默认被阻断；人可以用理由把这道闸推开，但推开本身必须留痕——
     * 操作者与时间由同一次裁决的 {@code decidedBy} / {@code decidedAt} 承担。
     */
    private String verificationOverrideReason;
    /**
     * 人工放行「证据链不完整」的理由。
     *
     * <p>研究报告会自评证据链是否完整；不完整时提升默认被阻断，人能写理由放行。
     * 操作者与时间同样由 {@code decidedBy} / {@code decidedAt} 承担。
     */
    private String evidenceOverrideReason;
}
