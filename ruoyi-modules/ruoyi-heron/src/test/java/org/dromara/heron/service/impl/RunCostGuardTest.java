package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.support.ModelPrice;
import org.dromara.heron.service.impl.shared.support.ModelPriceConfig;
import org.dromara.heron.service.impl.shared.support.RunCostGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 花费折算与单次 run 上限的口径：单价按「元 / 千 token」，上限按「达到即停」。
 *
 * <p>这几条边界是出真金白银的地方，必须能在没有数据库、没有模型的环境里验。
 */
@DisplayName("成本折算与 run 花费闸")
class RunCostGuardTest {

    private static ModelPrice price(String in, String out) {
        return new ModelPrice(new BigDecimal(in), new BigDecimal(out));
    }

    @Test
    @DisplayName("金额 = (输入 × 单价 + 输出 × 单价) / 1000，与手算一致")
    void amountMatchesHandCalculation() {
        ModelPrice price = price("0.002", "0.006");

        // (120000 × 0.002 + 40000 × 0.006) / 1000 = (240 + 240) / 1000
        assertEquals(new BigDecimal("0.4800"), price.amount(120_000, 40_000));
        assertEquals(new BigDecimal("0.0000"), price.amount(0, 0), "没有 token 就没有钱");
    }

    @Test
    @DisplayName("单价没配时金额为 0 且标记为不可信")
    void unpricedIsZeroButNotTrusted() {
        ModelPrice unknown = new ModelPrice(null, null);
        assertFalse(unknown.priced());
        assertEquals(0, unknown.amount(1_000, 1_000).signum());
    }

    @Test
    @DisplayName("上限没配、单价没配、或配成 0 时闸不生效")
    void noCeilingMeansNoCeiling() {
        assertNull(RunCostGuard.breach(10_000, 10_000, price("1", "1"), null), "没配上限就不设天花板");
        assertNull(RunCostGuard.breach(10_000, 10_000, price("1", "1"), BigDecimal.ZERO), "0 不等于一分钱都不许花");
        assertNull(RunCostGuard.breach(10_000, 10_000, price("1", "1"), new BigDecimal("-1")));
        assertNull(RunCostGuard.breach(10_000, 10_000, ModelPrice.UNKNOWN, new BigDecimal("1")),
            "单价没配就算不出钱，不能凭猜的价格停生产");
    }

    @Test
    @DisplayName("差一点点不拦，达到上限就拦")
    void breachStartsAtTheCeiling() {
        ModelPrice price = price("1", "1");
        BigDecimal ceiling = new BigDecimal("10");

        assertNull(RunCostGuard.breach(9_999, 0, price, ceiling), "9.999 元还没到 10 元，不该停");

        String atCeiling = RunCostGuard.breach(10_000, 0, price, ceiling);
        assertNotNull(atCeiling, "刚好到线就该停：这一阶段的调用已经把钱花掉了");
        assertTrue(atCeiling.contains("10 元已达上限 10 元"), atCeiling);
        assertTrue(atCeiling.contains("输入 10000 token"), atCeiling);
    }

    @Test
    @DisplayName("超出上限时原因里带得出累计金额、上限与 token 数")
    void breachCarriesTheReason() {
        ModelPrice price = price("0.5", "0.5");
        String reason = RunCostGuard.breach(30_000, 6_000, price, new BigDecimal("3"));

        assertNotNull(reason);
        assertTrue(reason.contains("18 元已达上限 3 元"), reason);
        assertTrue(reason.contains("输入 30000 token、输出 6000 token"), reason);
        assertTrue(reason.endsWith("已终止"), "上限必须是终止，不是提醒");
    }

    @Test
    @DisplayName("配置读不到时给的是空单价与不设上限，而不是异常")
    void configIsTolerantWhenMissing() {
        ModelPriceConfig missing = new ModelPriceConfig(key -> null);

        assertFalse(missing.price().priced());
        assertNull(missing.maxCost());
    }

    @Test
    @DisplayName("配置写成非数字时按未配置处理，不抛异常也不假装算过")
    void configFallsBackOnGarbage() {
        ModelPriceConfig garbage = new ModelPriceConfig(key -> switch (key) {
            case ModelPriceConfig.PRICE_IN_KEY -> "两分钱";
            case ModelPriceConfig.PRICE_OUT_KEY -> "0.004";
            case ModelPriceConfig.RUN_MAX_COST_KEY -> "五块";
            default -> null;
        });

        assertEquals(new BigDecimal("0.004"), garbage.price().priceOut());
        assertNull(garbage.maxCost(), "上限读不懂就是没上限，不能变成 0 把所有生产停掉");
    }

    @Test
    @DisplayName("sys_config 优先，其次回落 Environment")
    void readsSysConfigThenEnvironment() {
        org.dromara.system.api.ConfigService configService = mock(org.dromara.system.api.ConfigService.class);
        when(configService.getConfigValue(ModelPriceConfig.PRICE_IN_KEY)).thenReturn("0.01");
        ModelPriceConfig config = ModelPriceConfig.of(configService, new org.springframework.mock.env.MockEnvironment()
            .withProperty(ModelPriceConfig.PRICE_IN_KEY, "0.99")
            .withProperty(ModelPriceConfig.PRICE_OUT_KEY, "0.02"));

        assertEquals(new BigDecimal("0.01"), config.price().priceIn(), "sys_config 有值就不看 Environment");
        assertEquals(new BigDecimal("0.02"), config.price().priceOut(), "sys_config 空才回落");
    }
}
