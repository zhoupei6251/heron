package org.dromara.heron.service.impl.shared.support;

import lombok.extern.slf4j.Slf4j;
import org.dromara.system.api.ConfigService;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * 单价与单次 run 上限的读入口：读序 sys_config → Environment，与模型三项配置同一套规矩。
 *
 * <p>为什么放在配置而不是写死常量：单价会变，天花板必须由部署侧决定。
 * 单价的单位是「元 / 千 token」，写进 sys_config 的两行就是：
 * {@code heron.model.price-in} 与 {@code heron.model.price-out}。
 *
 * <p>上限读不到、为 0 或解析失败一律表示「没设天花板」：宁可让闸默认不开，
 * 也不要因为一个拼错的配置把所有生产停掉。
 */
@Slf4j
public final class ModelPriceConfig {

    /** 元 / 千输入 token。 */
    public static final String PRICE_IN_KEY = "heron.model.price-in";
    /** 元 / 千输出 token。 */
    public static final String PRICE_OUT_KEY = "heron.model.price-out";
    /** 单次 run（同一 task + attempt）的花费上限，单位元。 */
    public static final String RUN_MAX_COST_KEY = "heron.run.max-cost";

    private final Function<String, String> settings;

    /** 测试入口：直接给一个 key → value 的查询。 */
    public ModelPriceConfig(Function<String, String> settings) {
        this.settings = settings;
    }

    /** 生产入口：sys_config 优先，留空回落 Environment（yml 或环境变量）。 */
    public static ModelPriceConfig of(ConfigService config, Environment environment) {
        return new ModelPriceConfig(key -> {
            String value = config == null ? null : config.getConfigValue(key);
            if ((value == null || value.isBlank()) && environment != null) {
                value = environment.getProperty(key);
            }
            return value == null || value.isBlank() ? null : value.trim();
        });
    }

    /**
     * 每千 token 的输入/输出单价（元）；配置缺失或非法时按 0 处理。
     *
     * @return 单价对象，不会为 null
     */
    public ModelPrice price() {
        return new ModelPrice(decimal(PRICE_IN_KEY), decimal(PRICE_OUT_KEY));
    }

    /**
     * 单次 run 的花费上限（元）。
     *
     * @return null 表示没有上限；调用方据此跳过闸，不把 0 当成「一分钱都不许花」
     */
    public BigDecimal maxCost() {
        BigDecimal value = decimal(RUN_MAX_COST_KEY);
        return value == null || value.signum() <= 0 ? null : value;
    }

    private BigDecimal decimal(String key) {
        String value = settings.apply(key);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            // 配置写错不该把生产停掉，也不该静默当成 0 之后又看起来「算过了」
            log.warn("配置 {} 不是数字，按未配置处理：{}", key, value);
            return BigDecimal.ZERO;
        }
    }
}
