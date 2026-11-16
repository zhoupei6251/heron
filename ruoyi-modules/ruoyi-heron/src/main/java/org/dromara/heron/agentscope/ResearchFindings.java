package org.dromara.heron.agentscope;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次研究的交付物：模型主动收尾时写进来的结论，以及它引用的证据。
 *
 * <p>为什么要有它：plan.md §5.3 的七条结束条件里，token/时间/轮数是硬闸，
 * 另外四条（证据已足、边际价值低、数据取不到、数据冲突）只能由模型判断。
 * 没有收尾工具，模型就只能一路查到轮数到顶——那不叫「知道什么时候该停」。
 *
 * <p>证据同样在这里收：模型自己说「哪几条调用支撑了这个判断」，跑完后拿去和账本核对。
 * 不这么做的话，结论和证据之间只有一段自然语言，谁也证实不了它引的是哪次调用。
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
    /** 模型声明的证据来源：数据源 key 或机会 id，按出现顺序去重。 */
    private final List<String> cited = new ArrayList<>();

    void record(String decisionType, String reason, String angle, BigDecimal confidence,
                List<String> citations) {
        this.decisionType = decisionType;
        this.reason = reason;
        this.angle = angle;
        this.confidence = confidence;
        this.cited.clear();
        if (citations != null) {
            for (String item : citations) {
                String trimmed = item == null ? "" : item.trim();
                if (!trimmed.isEmpty() && !cited.contains(trimmed)) {
                    cited.add(trimmed);
                }
            }
        }
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

    public List<String> cited() {
        return List.copyOf(cited);
    }
}
