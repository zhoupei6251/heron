package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronContentDecision;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 内容决策展示对象。
 */
@Data
@AutoMapper(target = HeronContentDecision.class)
public class ContentDecisionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long decisionId;
    private Long researchId;
    private Integer attemptNo;
    private Long opportunityId;
    private String decisionType;
    private BigDecimal confidence;
    private String decisionReason;
    /** 依据的快照标识；为空表示当时没有可绑定的快照。 */
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
    /** 核验判 DISAGREE 时人工放行的理由；为空表示没有覆盖。 */
    private String verificationOverrideReason;
    /** 证据链不完整时人工放行的理由；为空表示没有覆盖。 */
    private String evidenceOverrideReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
