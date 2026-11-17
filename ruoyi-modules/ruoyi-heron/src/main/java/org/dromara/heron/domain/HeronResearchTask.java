package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 研究任务：一次研究对应一次 Agent 会话。
 *
 * <p>与生产任务分开建表：生产是「1 版本 1 任务」，研究是「1 次研究 : N 个候选」，
 * 且研究要带预算、可重跑、可只重跑某一步。状态词表与生产保持一致，只多一个 {@code partial}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_research_task")
public class HeronResearchTask extends BaseEntity {

    /** 排队中。 */
    public static final String STATUS_QUEUED = "queued";
    /** 执行中（持租约）。 */
    public static final String STATUS_RUNNING = "running";
    /** 正常结束。 */
    public static final String STATUS_SUCCEEDED = "succeeded";
    /** 预算或证据不足而提前收尾：结论可用但不完整。 */
    public static final String STATUS_PARTIAL = "partial";
    /** 失败。 */
    public static final String STATUS_FAILED = "failed";
    /**
     * 取消中：已收到取消请求，但执行方还没真的退出。
     *
     * <p>中间态不是装饰：执行中的 Agent 最长要等续命线程下一拍（15 秒）才会停，
     * 这期间库里直接写 {@code cancelled} 是在撒谎——任务还在烧钱，却已经显示成终态、
     * 还能被立刻重跑。所以它必须先停在这里。
     */
    public static final String STATUS_CANCELLING = "cancelling";
    /** 取消：执行方已确认退出。 */
    public static final String STATUS_CANCELLED = "cancelled";

    @TableId private Long researchId;
    private String researchNo;
    private Long opportunityId;
    private Long ownerId;
    private Long actorId;
    /** 研究目标，人读得懂的自然语言。 */
    private String goal;
    /** 判断依据的冻结引用（机会 id 或快照键）。 */
    private String snapshotRef;
    /** L1 / L2 / L3，预留分级分析。 */
    private String grade;
    private String status;
    /** PRODUCE / OBSERVE / REJECT / NEED_REVIEW。 */
    private String decisionType;
    private BigDecimal score;
    private BigDecimal confidence;
    private Integer rounds;
    private Integer toolCalls;
    private Long inputTokens;
    private Long outputTokens;
    /** Agent 会话标识：同一个研究重试时复用，不新建会话。 */
    private String sessionId;
    private String promptVersion;
    private Integer attemptNo;
    /**
     * 本次执行的令牌：领取时生成，之后心跳与所有写回都必须对上它。
     * 旧执行者拿不到匹配，就只能原地退出，不能再改这一行。
     */
    private String runToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    /** 模型的完整结论文本。 */
    private String resultText;
    /**
     * 证据链 JSON：结论引用了哪些调用、哪些引用在账本里查不到。
     * 只存引用关系不存原始返回——账本里已经有了，存两遍迟早对不上。
     */
    private String evidenceJson;
    /**
     * 结构化研究报告：结论、要点、风险，以及它依据的那份快照标识（data_sha256 + retrieved_at）。
     * 自然语言的 {@code resultText} 给人看，这份给下游用。
     */
    private String reportJson;
    private String errorCode;
    private String errorReason;
    /** 同一机会同一天只研究一次。 */
    private String dedupKey;
}
