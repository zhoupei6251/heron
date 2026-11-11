package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 东财宏观指标 → dataset/1（wide，月频）；一个指标一个 source_key。 */
public final class EastmoneyMacroAdapter implements DatasetAdapter {

    public record MacroSpec(String sourceKey, String report, String field, String label, String unit,
                            int precision) { }

    public static final List<MacroSpec> ALL = List.of(
        new MacroSpec("eastmoney-cpi", "RPT_ECONOMY_CPI", "NATIONAL_SAME", "CPI 同比", "%", 1),
        new MacroSpec("eastmoney-ppi", "RPT_ECONOMY_PPI", "BASE_SAME", "PPI 同比", "%", 1),
        new MacroSpec("eastmoney-pmi", "RPT_ECONOMY_PMI", "MAKE_INDEX", "制造业 PMI", "指数", 1),
        new MacroSpec("eastmoney-pmi-non", "RPT_ECONOMY_PMI", "NMAKE_INDEX", "非制造业 PMI", "指数", 1),
        new MacroSpec("eastmoney-gdp", "RPT_ECONOMY_GDP", "SUM_SAME", "GDP 同比", "%", 1));

    private static final String URL = "https://datacenter-web.eastmoney.com/api/data/v1/get"
        + "?reportName=%s&columns=ALL&pageSize=%d&sortColumns=REPORT_DATE&sortTypes=-1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_MONTHS = 60;

    private final MacroSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public EastmoneyMacroAdapter(MacroSpec spec) {
        this.spec = spec;
    }

    @Override
    public String sourceKey() {
        return spec.sourceKey();
    }

    @Override
    public String shape() {
        return "wide";
    }

    @Override
    public String fetch(int limit) {
        String url = URL.formatted(spec.report(), Math.max(2, Math.min(limit, MAX_MONTHS)));
        String body = DatasetHttp.get(http, url, Map.of(
            "User-Agent", "Mozilla/5.0", "Referer", "https://data.eastmoney.com/"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(MacroSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode data;
        try {
            data = JSON.readTree(body).path("result").path("data");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("宏观指标不是 JSON");
        }
        if (!data.isArray() || data.size() < 2) {
            throw DatasetHttp.invalid("宏观指标数据不足两行");
        }
        TreeMap<String, BigDecimal> byDate = new TreeMap<>();
        for (JsonNode item : data) {
            String report = item.path("REPORT_DATE").asString("");
            JsonNode value = item.path(spec.field());
            if (report.length() < 10 || !value.isNumber()) continue;
            String date = report.substring(0, 10);
            byDate.put(date, BigDecimal.valueOf(value.asDouble()).setScale(spec.precision(), RoundingMode.HALF_UP));
        }
        if (byDate.size() < 2) {
            throw DatasetHttp.invalid("宏观指标有效行不足两行");
        }
        List<DatasetFacts.Row> all = new ArrayList<>();
        byDate.forEach((date, value) -> all.add(new DatasetFacts.Row(List.of(date, value))));
        int from = Math.max(0, all.size() - limit);
        List<DatasetFacts.Row> rows = all.subList(from, all.size());
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "东方财富", sourceUrl,
            spec.field().toLowerCase(), spec.label(), spec.unit(), spec.precision(), "monthly", "wide",
            List.of("date", "value")), rows, retrievedAt);
    }
}
