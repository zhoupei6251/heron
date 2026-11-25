package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 研究与决策的成本概览，给监督工作台用。
 *
 * <p>为什么单独出这个视图而不是让前端拉全量任务自己算：成本是要**长期看趋势**的东西，
 * 前端算不了分档均值，也算不了「每个档位花了多少」。
 */
@Data
public class ResearchCostVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 统计窗口天数。 */
    private int days;
    /** 研究任务数。 */
    private long researchCount;
    /** 已成为决策的研究数（有结论的）。 */
    private long decidedCount;
    /** 输入 / 输出 token 合计。 */
    private long inputTokens;
    private long outputTokens;
    /** 平均每次研究的 token。 */
    private long avgTokens;
    /** 按当前单价折算的金额合计（元）。单价未配置时恒为 0。 */
    private BigDecimal amount;
    /** 金额是否可信：false 表示 {@code heron.model.price-in} / {@code price-out} 没配，金额没意义。 */
    private boolean priced;
    /** 工具调用合计。 */
    private long toolCalls;
    /** 按等级汇总。 */
    private List<GradeCost> byGrade;
    /** 按决策类型汇总。 */
    private List<DecisionCount> byDecision;
    /** 核验次数与冲突次数。 */
    private long verifiedCount;
    private long conflictCount;
    /** 已采纳的决策数。 */
    private long acceptedCount;
    /** 采纳率 = 已采纳 / 已决策。样本少时不该当成 KPI 看。 */
    private BigDecimal acceptanceRate;

    /**
     * 一个等级的成本与效果。
     *
     * @param grade      L1 / L2 / L3
     * @param count      任务数
     * @param avgTokens  平均 token：分级的全部意义就是让这个数随档位上升
     * @param succeeded  成功的任务数
     */
    public record GradeCost(String grade, long count, long avgTokens, long succeeded) {
    }

    /**
     * 一种决策的数量。
     *
     * @param decisionType PRODUCE / OBSERVE / REJECT
     * @param count        数量
     * @param accepted     其中被人采纳的
     */
    public record DecisionCount(String decisionType, long count, long accepted) {
    }
}
