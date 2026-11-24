package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 研究期间的一次工具调用。
 *
 * <p>只记摘要不记原始返回：工具会碰到业务数据，全量留档等于复制一份数据出去。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_agent_tool_call")
public class HeronAgentToolCall extends BaseEntity {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_FAILED = "failed";

    @TableId private Long id;
    private Long researchId;
    private Integer attemptNo;
    private Integer roundNo;
    private String toolName;
    private String toolUseId;
    /** 入参摘要，截断。 */
    private String requestSummary;
    /** 出参摘要，截断。 */
    private String responseSummary;
    private String status;
    private Long durationMs;
    private String errorCode;
}
