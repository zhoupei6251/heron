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

/** Gapminder 静态数据 → dataset/1（wide：各国平均生育率逐年，未加权）。 */
public final class GapminderFertilityAdapter implements DatasetAdapter {

    public static final String SOURCE_KEY = "gapminder-fertility-world";
    private static final String URL = "https://raw.githubusercontent.com/vega/vega-datasets/main/data/gapminder.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PRECISION = 2;

    private final HttpClient http = DatasetHttp.client();

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public String shape() {
        return "wide";
    }

    @Override
    public String fetch(int limit) {
        String body = DatasetHttp.get(http, URL, Map.of("User-Agent", "Mozilla/5.0"));
        return build(body, URL, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode rowsNode;
        try {
            rowsNode = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("Gapminder 数据不是 JSON");
        }
        if (!rowsNode.isArray()) {
            throw DatasetHttp.invalid("Gapminder 数据不是数组");
        }
        Map<Integer, double[]> sums = new TreeMap<>();
        for (JsonNode row : rowsNode) {
            JsonNode fertility = row.path("fertility");
            JsonNode year = row.path("year");
            if (!fertility.isNumber() || !year.isNumber()) continue;
            double[] bucket = sums.computeIfAbsent(year.asInt(), key -> new double[2]);
            bucket[0] += fertility.asDouble();
            bucket[1] += 1;
        }
        if (sums.size() < 2) {
            throw DatasetHttp.invalid("Gapminder 有效年份不足两年");
        }
        List<DatasetFacts.Row> all = new ArrayList<>();
        sums.forEach((year, bucket) -> {
            double mean = bucket[0] / bucket[1];
            all.add(new DatasetFacts.Row(List.of(year + "-01-01",
                BigDecimal.valueOf(mean).setScale(PRECISION, RoundingMode.HALF_UP))));
        });
        int from = Math.max(0, all.size() - limit);
        List<DatasetFacts.Row> rows = all.subList(from, all.size());
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(SOURCE_KEY, "Gapminder", sourceUrl,
            "fertility", "各国平均生育率（未加权）", "个", PRECISION, "annual", "wide",
            List.of("date", "value")), rows, retrievedAt);
    }
}
