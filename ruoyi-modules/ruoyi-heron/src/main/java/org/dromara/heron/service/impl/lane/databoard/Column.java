package org.dromara.heron.service.impl.lane.databoard;

/** 五个常设栏目 + 其他；权重用于路由与速览轮换。 */
public enum Column {

    TECH("tech", "科技竞争力", 30),
    MARKET("market", "全球市场", 20),
    BUSINESS("business", "商业观察室", 20),
    TREND("trend", "趋势解码", 15),
    ATLAS("atlas", "全球数据图鉴", 15),
    OTHER("other", "其他", 0);

    private final String key;
    private final String name;
    private final int weight;

    Column(String key, String name, int weight) {
        this.key = key;
        this.name = name;
        this.weight = weight;
    }

    /** 稳定 key，落库与提示词里用它，不用枚举名。 */
    public String key() {
        return key;
    }

    /** 页面展示名。 */
    public String displayName() {
        return name;
    }

    /** 路由与速览轮换的权重。 */
    public int weight() {
        return weight;
    }

    /**
     * 按 sourceKey 前缀归属栏目。
     *
     * <p>eastmoney 内部分得更细：市值进商业、新能源与 CO2 进趋势、半导体与 TMT 进科技，
     * 其余东财源里带 cpi/ppi/pmi/gdp 的进趋势，剩下的进市场。
     *
     * @param sourceKey 注册表里的源 key，可为 null
     * @return 所属栏目；认不出来时返回 {@link #OTHER}
     */
    public static Column forSource(String sourceKey) {
        String key = sourceKey == null ? "" : sourceKey;
        if (key.startsWith("eastmoney-marketcap")) return BUSINESS;
        if (key.startsWith("eastmoney-nev") || key.startsWith("owid-co2")) return TREND;
        if (key.startsWith("eastmoney-semi") || key.startsWith("eastmoney-tmt")) return TECH;
        if (key.startsWith("eastmoney-")) {
            return key.contains("cpi") || key.contains("ppi") || key.contains("pmi") || key.contains("gdp")
                ? TREND : MARKET;
        }
        if (key.startsWith("sina-") || key.startsWith("frankfurter")) return MARKET;
        if (key.startsWith("worldbank-") || key.startsWith("owid-") || key.startsWith("gapminder-")
            || key.startsWith("usgs-")) return ATLAS;
        if (key.startsWith("huggingface-") || key.startsWith("github-") || key.startsWith("stackexchange-")
            || key.startsWith("devto-") || key.startsWith("wikimedia-") || key.startsWith("jikan-")
            || key.startsWith("cheapshark-")) return TECH;
        return OTHER;
    }
}
