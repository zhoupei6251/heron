package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * World Bank 指标 → dataset/1（rows：主要经济体按指标排序）。
 * 年度数据，适合周更/专题；用 mrnev=1 取每国最近一个非空年份。
 */
public final class WorldBankRankingAdapter implements DatasetAdapter {

    public record IndicatorSpec(String sourceKey, String indicator, String label, String unit, double scale,
                                int precision) { }

    public static final List<IndicatorSpec> ALL = List.of(
        new IndicatorSpec("worldbank-gdp", "NY.GDP.MKTP.CD", "GDP（现价美元）", "十亿美元", 1e9, 0),
        new IndicatorSpec("worldbank-population", "SP.POP.TOTL", "总人口", "百万人", 1e6, 1),
        new IndicatorSpec("worldbank-life-expectancy", "SP.DYN.LE00.IN", "预期寿命", "岁", 1, 1),
        new IndicatorSpec("worldbank-co2-per-capita", "EN.ATM.CO2E.PC", "人均碳排放", "吨/人", 1, 2),
        new IndicatorSpec("worldbank-unemployment", "SL.UEM.TOTL.ZS", "失业率", "%", 1, 1),
        new IndicatorSpec("worldbank-inflation", "FP.CPI.TOTL.ZG", "通胀率", "%", 1, 1),
        new IndicatorSpec("worldbank-military", "MS.MIL.XPND.GD.ZS", "军费占 GDP", "%", 1, 1),
        new IndicatorSpec("worldbank-internet", "IT.NET.USER.ZS", "互联网普及率", "%", 1, 1),
        new IndicatorSpec("worldbank-gdp-growth", "NY.GDP.MKTP.KD.ZG", "GDP 增长率", "%", 1, 1),
        new IndicatorSpec("worldbank-gdp-per-capita", "NY.GDP.PCAP.CD", "人均 GDP", "美元", 1, 0),
        new IndicatorSpec("worldbank-fertility", "SP.DYN.TFRT.IN", "生育率", "个", 1, 2),
        new IndicatorSpec("worldbank-urbanization", "SP.URB.TOTL.IN.ZS", "城镇化率", "%", 1, 1),
        new IndicatorSpec("worldbank-agriculture", "NV.AGR.TOTL.ZS", "农业占 GDP", "%", 1, 1),
        new IndicatorSpec("worldbank-industry", "NV.IND.TOTL.ZS", "工业占 GDP", "%", 1, 1),
        new IndicatorSpec("worldbank-services", "NV.SRV.TOTL.ZS", "服务业占 GDP", "%", 1, 1),
        new IndicatorSpec("worldbank-health", "SH.XPD.CHEX.GD.ZS", "医疗支出占 GDP", "%", 1, 1));

    private static final String COUNTRIES = "USA;CHN;JPN;DEU;IND;GBR;FRA;BRA;CAN;KOR;ITA;RUS;ESP;AUS;MEX;IDN;TUR;SAU;CHE;SWE";
    private static final String URL = "https://api.worldbank.org/v2/country/%s/indicator/%s"
        + "?format=json&mrnev=1&per_page=100";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final IndicatorSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public WorldBankRankingAdapter(IndicatorSpec spec) {
        this.spec = spec;
    }

    @Override
    public String sourceKey() {
        return spec.sourceKey();
    }

    @Override
    public String shape() {
        return "rows";
    }

    @Override
    public String fetch(int limit) {
        int cap = Math.max(2, Math.min(limit, MAX_ROWS));
        String url = URL.formatted(COUNTRIES, spec.indicator());
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "heron-news/0.1"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Entry(String country, BigDecimal value) { }

    static String build(IndicatorSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode rows;
        try {
            rows = JSON.readTree(body).path(1);
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("世行数据不是 JSON");
        }
        if (!rows.isArray()) {
            throw DatasetHttp.invalid("世行数据缺少结果数组");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode row : rows) {
            String country = row.path("country").path("value").asString("");
            JsonNode value = row.path("value");
            if (country.isBlank() || !value.isNumber()) continue;
            entries.add(new Entry(country,
                BigDecimal.valueOf(value.asDouble() / spec.scale()).setScale(spec.precision(), RoundingMode.HALF_UP)));
        }
        if (entries.size() < 2) {
            throw DatasetHttp.invalid("世行有效条目不足两条");
        }
        entries.sort(Comparator.comparing(Entry::value).reversed());
        List<DatasetFacts.Row> cells = new ArrayList<>();
        for (Entry entry : entries.subList(0, Math.min(limit, entries.size()))) {
            cells.add(new DatasetFacts.Row(List.of(entry.country(), entry.value())));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "World Bank", sourceUrl,
            spec.indicator().toLowerCase(), spec.label(), spec.unit(), spec.precision(), "annual", "rows",
            List.of("label", "value")), cells, retrievedAt);
    }
}
