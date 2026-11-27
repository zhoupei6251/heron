package org.dromara.heron.agentscope.research;

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
    /** 支撑判断的关键要点，每条一句。 */
    private final List<String> keyInsights = new ArrayList<>();
    /** 风险与不确定性：口径可疑、样本不足、无法交叉验证等。 */
    private final List<String> riskWarnings = new ArrayList<>();
    /** 模型声明的证据来源：数据源 key 或机会 id，按出现顺序去重。 */
    private final List<String> cited = new ArrayList<>();

    /** 整体替换上一次结论：decision 归一化为大写，列表去空白、去重后保存。 */
    public void record(String decisionType, String reason, String angle, BigDecimal confidence,
                List<String> insights, List<String> risks, List<String> citations) {
        // 归一化放在存储边界：工具入口校验完就归一化过一次，但换个调用路径（重放、测试）就会漏
        this.decisionType = decisionType == null ? null : decisionType.trim().toUpperCase();
        this.reason = reason;
        this.angle = angle;
        this.confidence = confidence;
        replaceAll(keyInsights, insights);
        replaceAll(riskWarnings, risks);
        replaceAll(cited, citations);
    }

    private static void replaceAll(List<String> target, List<String> source) {
        target.clear();
        if (source == null) {
            return;
        }
        for (String item : source) {
            String trimmed = item == null ? "" : item.trim();
            if (!trimmed.isEmpty() && !target.contains(trimmed)) {
                target.add(trimmed);
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

    public List<String> keyInsights() {
        return List.copyOf(keyInsights);
    }

    public List<String> riskWarnings() {
        return List.copyOf(riskWarnings);
    }
}
