package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronResearchTask;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 研究任务展示对象。
 */
@Data
@AutoMapper(target = HeronResearchTask.class)
public class ResearchTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String researchNo;
    private Long opportunityId;
    private String goal;
    private String grade;
    private String status;
    private String decisionType;
    private BigDecimal score;
    private BigDecimal confidence;
    private Integer rounds;
    private Integer toolCalls;
    private Long inputTokens;
    private Long outputTokens;
    private String sessionId;
    private Integer attemptNo;
    private String resultText;
    /** 证据链 JSON：结论引用了哪些调用、哪些引用在账本里查不到。 */
    private String evidenceJson;
    /** 结构化研究报告：结论、要点、风险与快照绑定。 */
    private String reportJson;
    private String errorCode;
    private String errorReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
