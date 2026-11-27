package org.dromara.heron.agentscope.research;

import java.time.Duration;

/**
 * 三级智能分析策略（plan.md §12）。
 *
 * <p>分级不是三套参数，是三种执行器：L1 普通 LLM、L2 ReActAgent、L3 HarnessAgent。
 * 上游「初始机会筛选尽量由 Java 完成」，只有到阈值才升级——因为**成本不该随机会数线性增长**。
 *
 * <p>为什么按机会评分分级而不是让人选：人一定会全选最高档。默认值必须来自数据本身。
 *
 * <table>
 *   <tr><th>等级</th><th>执行器</th><th>适用</th></tr>
 *   <tr><td>L1</td><td>普通 LLM</td><td>普通数据摘要、简单排行</td></tr>
 *   <tr><td>L2</td><td>ReActAgent</td><td>异常增长、趋势分析</td></tr>
 *   <tr><td>L3</td><td>HarnessAgent + 独立核验</td><td>高价值选题、复杂研究</td></tr>
 * </table>
 */
public enum ResearchGrade {

    /** 普通数据摘要：一次模型调用，不查工具。 */
    L1("L1", 1.0),
    /** 异常增长与趋势：ReAct 循环，查工具但预算更紧。 */
    L2("L2", 2.0),
    /** 高价值选题：完整 Harness + 独立核验。 */
    L3("L3", 3.0);

    /**
     * 默认升级阈值：机会评分（0-100）。
     *
     * <p>L1 覆盖「看起来平淡」的大多数；L3 只给真正突出的。这两个数是起点不是定论——
     * 它们决定的是成本曲线，应该按实际账单调，不该写成常量就再也不看。
     */
    static final double L2_THRESHOLD = 55.0;
    static final double L3_THRESHOLD = 70.0;

    private final String code;
    private final double budgetFactor;

    ResearchGrade(String code, double budgetFactor) {
        this.code = code;
        this.budgetFactor = budgetFactor;
    }

    /** @return 存库用的等级码（L1 / L2 / L3） */
    public String code() {
        return code;
    }

    /**
     * 预算倍数：档位越高允许花的越多，但都是基准值的整数倍，便于推算最坏情况。
     */
    public double budgetFactor() {
        return budgetFactor;
    }

    /** 是否查工具：L1 是纯文本摘要，没有工具面。 */
    public boolean usesTools() {
        return this != L1;
    }

    /** 是否跑独立核验：只有最高档值得再花一次完整 Agent 调用。 */
    public boolean verifies() {
        return this == L3;
    }

    /** @return true 表示走裸 ReActAgent（L2）；L3 走 HarnessAgent */
    public boolean usesReActOnly() {
        return this == L2;
    }

    /**
     * 按机会评分定档。
     *
     * @param score 机会评分；为 null 时按最低档处理——没评分就别花大钱
     * @return 达到 70 为 L3、达到 55 为 L2，其余为 L1
     */
    public static ResearchGrade of(Double score) {
        if (score == null) {
            return L1;
        }
        if (score >= L3_THRESHOLD) {
            return L3;
        }
        if (score >= L2_THRESHOLD) {
            return L2;
        }
        return L1;
    }

    /**
     * 解析存库的等级码；认不出来时退回 L2（有工具、不核验），不静默降到最便宜的一档。
     *
     * @param code 等级码，大小写不敏感
     * @return 匹配的档位；null 或无法识别时为 L2
     */
    public static ResearchGrade parse(String code) {
        if (code == null) {
            return L2;
        }
        for (ResearchGrade grade : values()) {
            if (grade.code.equalsIgnoreCase(code.trim())) {
                return grade;
            }
        }
        return L2;
    }

    /**
     * 各档的执行时长上限：高价值才值得等更久。
     *
     * @param base 基准时长
     * @return L1 为基准的三分之一，L2/L3 与基准相同
     */
    public Duration maxDuration(Duration base) {
        return switch (this) {
            case L1 -> base.dividedBy(3);
            case L2 -> base;
            case L3 -> base;
        };
    }
}
