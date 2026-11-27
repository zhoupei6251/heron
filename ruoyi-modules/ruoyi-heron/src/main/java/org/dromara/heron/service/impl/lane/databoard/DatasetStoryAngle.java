package org.dromara.heron.service.impl.lane.databoard;

import tools.jackson.databind.JsonNode;

/**
 * 故事角度：线内编辑按机会自己的信号选叙事角度，不再把编辑意图抄一遍当角度。
 *
 * <p>叙事类型不是自由发挥的：先看这份数据满足哪几条机器前提，再在满足的里选，
 * 前提表与 {@code docs/plans/2026-11-19-data-board-story-plan.md} §6 同源。
 *
 * <p>角度文本里既不写数字也不写时间口径：旁白的数字只能来自占位符，区间口径只能来自
 * {@code facts.first.date} 到 {@code facts.last.date}。角度一旦自带「近 7 日」这种口径，
 * 写稿就会把区间变化说成短期变化——那是审稿要拦的错，不该由角度去引入。
 */
public final class DatasetStoryAngle {

    public static final String COUNTER_INTUITIVE = "counter_intuitive";
    public static final String RANK_COMPETITION = "rank_competition";
    public static final String NUMERIC_CONTRAST = "numeric_contrast";
    public static final String TIME_CHANGE = "time_change";
    public static final String PROBLEM_EVIDENCE_REVEAL = "problem_evidence_reveal";

    /** 没有任何强信号时的角度：老实按速览写，不硬凑故事。 */
    public static final String DIGEST_ANGLE = "按数据速览写：把区间变化讲清楚，不硬凑故事";

    /**
     * @param narrative 叙事类型
     * @param text 角度文本，进冻结输入的 storyAngle
     */
    public record Angle(String narrative, String text) { }

    private DatasetStoryAngle() { }

    /**
     * 按机会的信号选角度。
     *
     * @param shape 快照形态
     * @param signals 机会的信号 JSON（{@code DataOpportunityService.signalsJson} 的产物）
     * @param periodStart 区间起点
     * @param periodEnd 区间终点
     */
    public static Angle pick(String shape, JsonNode signals, String periodStart, String periodEnd) {
        if (signals == null || !signals.isObject()) {
            return new Angle(PROBLEM_EVIDENCE_REVEAL, text(PROBLEM_EVIDENCE_REVEAL));
        }
        if (signals.path("extreme").asBoolean(false) || Math.abs(signals.path("zscore").asDouble(0)) >= 2) {
            return new Angle(COUNTER_INTUITIVE, text(COUNTER_INTUITIVE));
        }
        if ("rows".equals(shape)) {
            return new Angle(RANK_COMPETITION, text(RANK_COMPETITION));
        }
        double changePct = signals.path("change_pct").asDouble(Double.NaN);
        double changeScale = signals.path("change_scale").asDouble(Double.NaN);
        if (!Double.isNaN(changePct) && !Double.isNaN(changeScale) && changeScale > 0
            && Math.abs(changePct) >= changeScale) {
            return new Angle(NUMERIC_CONTRAST, text(NUMERIC_CONTRAST));
        }
        double changeAbs = signals.path("change_abs").asDouble(0);
        if (periodStart != null && periodEnd != null && !periodStart.isBlank() && !periodEnd.isBlank()
            && !periodStart.equals(periodEnd) && Math.abs(changeAbs) > 0) {
            return new Angle(TIME_CHANGE, text(TIME_CHANGE));
        }
        return new Angle(PROBLEM_EVIDENCE_REVEAL, text(PROBLEM_EVIDENCE_REVEAL));
    }

    private static String text(String narrative) {
        return switch (narrative) {
            case COUNTER_INTUITIVE -> "从反常识切入：这次的走向和直觉相反，先给结论再摆数据";
            case RANK_COMPETITION -> "从排名竞争切入：名次在动，讲清楚谁上谁下、差距有多大";
            case NUMERIC_CONTRAST -> "从数字反差切入：把两个数放在一起才看得出问题";
            case TIME_CHANGE -> "从时间变化切入：把跨度讲出来，别把区间变化说成单日涨跌";
            default -> "从问题—证据—揭示切入：先提一个问题，再用两条独立事实回答";
        };
    }
}
