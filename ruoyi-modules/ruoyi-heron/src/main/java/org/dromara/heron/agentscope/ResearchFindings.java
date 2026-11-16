package org.dromara.heron.agentscope;

import java.math.BigDecimal;

/**
 * 一次研究的交付物：模型主动收尾时写进来的结论。
 *
 * <p>为什么要有它：plan.md §5.3 的七条结束条件里，token/时间/轮数是硬闸，
 * 另外四条（证据已足、边际价值低、数据取不到、数据冲突）只能由模型判断。
 * 没有收尾工具，模型就只能一路查到轮数到顶——那不叫「知道什么时候该停」。
 *
 * <p>可变且每次研究一份：工具写它，跑完后由 {@link AgentscopeResearchAgent} 读走并落库。
 */
public final class ResearchFindings {

    /** 值得做成一条视频。 */
    public static final String PRODUCE = "PRODUCE";
    /** 还需要更多数据。 */
    public static final String OBSERVE = "OBSERVE";
    /** 不值得做。 */
    public static final String REJECT = "REJECT";

    private String decisionType;
    private String reason;
    private String angle;
    private BigDecimal confidence;

    void record(String decisionType, String reason, String angle, BigDecimal confidence) {
        this.decisionType = decisionType;
        this.reason = reason;
        this.angle = angle;
        this.confidence = confidence;
    }

    /** 模型是否已经给出结论。 */
    public boolean submitted() {
        return decisionType != null;
    }

    public String decisionType() {
        return decisionType;
    }

    public String reason() {
        return reason;
    }

    public String angle() {
        return angle;
    }

    public BigDecimal confidence() {
        return confidence;
    }
}
