package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** USGS 地震目录 → dataset/1（wide：近 30 天每日某震级以上次数）。 */
public final class UsgsEarthquakeAdapter implements DatasetAdapter {

    public record QuakeSpec(String sourceKey, double magnitude, String label) { }

    public static final List<QuakeSpec> ALL = List.of(
        new QuakeSpec("usgs-earthquakes", 4.5, "全球 4.5 级以上地震"),
        new QuakeSpec("usgs-earthquakes-m55", 5.5, "全球 5.5 级以上地震"),
        new QuakeSpec("usgs-earthquakes-m60", 6.0, "全球 6.0 级以上地震"));

    private static final String URL = "https://earthquake.usgs.gov/fdsnws/event/1/query"
        + "?format=geojson&starttime=%s&endtime=%s&minmagnitude=%.1f&orderby=time";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int WINDOW_DAYS = 30;

    private final QuakeSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public UsgsEarthquakeAdapter(QuakeSpec spec) {
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
        int days = Math.max(2, Math.min(limit, WINDOW_DAYS));
        String url = URL.formatted(end.minusDays(days - 1L), end, spec.magnitude());
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "Mozilla/5.0"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), days);
    }

    static String build(QuakeSpec spec, String body, String sourceUrl, Instant retrievedAt, int days) {
        JsonNode features;
        try {
            features = JSON.readTree(body).path("features");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("地震目录不是 JSON");
        }
        if (!features.isArray()) {
            throw DatasetHttp.invalid("地震目录缺少 features");
        }
        LocalDate end = retrievedAt.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate start = end.minusDays(days - 1L);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            counts.put(date.toString(), 0);
        }
        for (JsonNode feature : features) {
            JsonNode time = feature.path("properties").path("time");
            if (!time.isNumber()) continue;
            String date = Instant.ofEpochMilli(time.asLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
            counts.computeIfPresent(date, (key, value) -> value + 1);
        }
        List<DatasetFacts.Row> rows = new ArrayList<>();
        counts.forEach((date, count) -> rows.add(new DatasetFacts.Row(List.of(date, new BigDecimal(count)))));
        String metricKey = "quakes_m" + String.valueOf(spec.magnitude()).replace(".", "");
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "USGS", sourceUrl, metricKey,
            spec.label(), "次/日", 0, "daily", "wide", List.of("date", "value")), rows, retrievedAt);
    }
}
