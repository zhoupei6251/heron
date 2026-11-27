package org.dromara.heron.service.impl.shared.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型单价的唯一折算口径：token × 单价 / 1000。
 *
 * <p>为什么单价不进账本表：单人项目没有跨期对账需求，加一列要多一次迁移加回填。
 * 历史记录一律按当前单价重算，界面上看到的是「按今天的价，这批 token 值多少钱」。
 *
 * <p>{@link #priced()} 存在的理由：单价没配时金额恒为 0，0 元不等于「没花钱」，
 * 而等于「这个数不可信」。读侧必须先看这个标记，再决定敢不敢把钱数给人看。
 *
 * @param priceIn  元 / 千输入 token
 * @param priceOut 元 / 千输出 token
 */
public record ModelPrice(BigDecimal priceIn, BigDecimal priceOut) {

    /** 未配置单价：两个乘数都是 0，折算出来的金额只用于占位。 */
    public static final ModelPrice UNKNOWN = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO);

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    /** 金额保留 4 位小数：分以下还有两位，够看清单条片子的成本结构。 */
    private static final int AMOUNT_SCALE = 4;

    public ModelPrice {
        priceIn = priceIn == null ? BigDecimal.ZERO : priceIn;
        priceOut = priceOut == null ? BigDecimal.ZERO : priceOut;
        if (priceIn.signum() < 0 || priceOut.signum() < 0) {
            throw new IllegalArgumentException("单价不能为负：" + priceIn + " / " + priceOut);
        }
    }

    /** 至少配了一个单价才算「算得出来」。 */
    public boolean priced() {
        return priceIn.signum() > 0 || priceOut.signum() > 0;
    }

    /** 一次 run 或一个统计窗口的钱：输入与输出分别乘单价后求和，再除 1000。 */
    public BigDecimal amount(long inputTokens, long outputTokens) {
        if (!priced()) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal input = BigDecimal.valueOf(Math.max(inputTokens, 0L)).multiply(priceIn);
        BigDecimal output = BigDecimal.valueOf(Math.max(outputTokens, 0L)).multiply(priceOut);
        return input.add(output).divide(THOUSAND, AMOUNT_SCALE, RoundingMode.HALF_UP);
    }
}
