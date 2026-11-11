package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Frankfurter 汇率 → dataset/1（wide）；一个货币对一个 source_key。 */
public final class FrankfurterAdapter implements DatasetAdapter {

    public record PairSpec(String sourceKey, String from, String to, String label) { }

    public static final List<PairSpec> ALL = List.of(
        new PairSpec("frankfurter", "USD", "CNY", "美元兑人民币"),
        new PairSpec("frankfurter-eurcny", "EUR", "CNY", "欧元兑人民币"),
        new PairSpec("frankfurter-jpycny", "JPY", "CNY", "日元兑人民币"),
        new PairSpec("frankfurter-gbpcny", "GBP", "CNY", "英镑兑人民币"),
        new PairSpec("frankfurter-hkdcny", "HKD", "CNY", "港币兑人民币"),
        new PairSpec("frankfurter-audcny", "AUD", "CNY", "澳元兑人民币"),
        new PairSpec("frankfurter-cadcny", "CAD", "CNY", "加元兑人民币"),
        new PairSpec("frankfurter-sgdcny", "SGD", "CNY", "新加坡元兑人民币"),
        new PairSpec("frankfurter-chfcny", "CHF", "CNY", "瑞士法郎兑人民币"),
        new PairSpec("frankfurter-nzdcny", "NZD", "CNY", "新西兰元兑人民币"),
        new PairSpec("frankfurter-sekcny", "SEK", "CNY", "瑞典克朗兑人民币"),
        new PairSpec("frankfurter-nokcny", "NOK", "CNY", "挪威克朗兑人民币"),
        new PairSpec("frankfurter-dkkcny", "DKK", "CNY", "丹麦克朗兑人民币"));

    private static final String BASE = "https://api.frankfurter.app";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PRECISION = 4;

    private final PairSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public FrankfurterAdapter(PairSpec spec) {
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
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        String url = BASE + "/" + end.minusDays(90) + ".." + end
            + "?from=" + spec.from() + "&to=" + spec.to();
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "Mozilla/5.0"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(PairSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode rates;
        try {
            rates = JSON.readTree(body).path("rates");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("汇率响应不是 JSON");
        }
        if (!rates.isObject()) {
            throw DatasetHttp.invalid("汇率响应缺少 rates");
        }
        TreeMap<String, BigDecimal> byDate = new TreeMap<>();
        for (String date : rates.propertyNames()) {
            JsonNode rate = rates.path(date).path(spec.to());
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}") || !rate.isNumber()) continue;
            byDate.put(date, BigDecimal.valueOf(rate.asDouble()).setScale(PRECISION, RoundingMode.HALF_UP));
        }
        if (byDate.size() < 2) {
            throw DatasetHttp.invalid("汇率数据不足两行");
        }
        List<DatasetFacts.Row> all = new ArrayList<>();
        byDate.forEach((date, value) -> all.add(new DatasetFacts.Row(List.of(date, value))));
        int from = Math.max(0, all.size() - limit);
        List<DatasetFacts.Row> rows = all.subList(from, all.size());
        String metricKey = spec.from().toLowerCase() + "_" + spec.to().toLowerCase();
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "Frankfurter", sourceUrl, metricKey,
            spec.label(), spec.to(), PRECISION, "daily", "wide", List.of("date", "value")), rows, retrievedAt);
    }
}
