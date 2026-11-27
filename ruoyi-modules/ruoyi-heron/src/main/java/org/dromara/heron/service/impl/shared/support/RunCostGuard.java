package org.dromara.heron.service.impl.shared.support;

import java.math.BigDecimal;

/**
 * 单次 run 的花费闸：只做判断，不查库、不抛异常。
 *
 * <p>为什么单独拆出来：上限是不是生效，取决于三个前提同时成立——配了单价、配了上限、
 * 累计金额真的到了线。三个前提混在一次数据库事务里就只能在生产环境上赌，拆开就都能验。
 *
 * <p>口径是「达到或超过」而不是「超过」：上限就是这条线的定义，
 * 花到上限的那一次调用已经发生，下一阶段不该再放行。
 */
public final class RunCostGuard {

    /** 失败原因码，{@code ProductionRunner} 会把它写进 {@code heron_production_task.error_code}。 */
    public static final String CODE = "run_cost_limit";

    private RunCostGuard() {
    }

    /**
     * @return null 表示没到线；否则是可直接写进 error_reason 的完整原因
     */
    public static String breach(long inputTokens, long outputTokens, ModelPrice price, BigDecimal max) {
        // 单价没配就算不出钱，上限配了也只是空转；宁可闸不动，也不要凭猜的价格停生产
        if (max == null || max.signum() <= 0 || price == null || !price.priced()) {
            return null;
        }
        BigDecimal spent = price.amount(inputTokens, outputTokens);
        if (spent.compareTo(max) < 0) {
            return null;
        }
        return "本次运行累计花费 " + plain(spent) + " 元已达上限 " + plain(max) + " 元（输入 " + Math.max(inputTokens, 0L)
            + " token、输出 " + Math.max(outputTokens, 0L) + " token），已终止";
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
