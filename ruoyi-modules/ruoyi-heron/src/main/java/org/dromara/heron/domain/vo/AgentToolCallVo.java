package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronAgentToolCall;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具调用展示对象。
 */
@Data
@AutoMapper(target = HeronAgentToolCall.class)
public class AgentToolCallVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long callId;
    private Long researchId;
    private Integer attemptNo;
    private Integer roundNo;
    private String toolName;
    private String requestSummary;
    private String responseSummary;
    private String status;
    private Long durationMs;
    private String errorCode;
    private LocalDateTime createTime;
}
