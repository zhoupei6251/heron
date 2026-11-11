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

/** vega-datasets 的 IMDb 电影数据 → dataset/1（rows：全球票房榜）。 */
public final class VegaMoviesAdapter implements DatasetAdapter {

    public static final String SOURCE_KEY = "vega-movies-gross";
    private static final String URL = "https://raw.githubusercontent.com/vega/vega-datasets/main/data/movies.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double SCALE = 1e6;
    private static final int MAX_ROWS = 20;

    private final HttpClient http = DatasetHttp.client();

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public String shape() {
        return "rows";
    }

    @Override
    public String fetch(int limit) {
        String body = DatasetHttp.get(http, URL, Map.of("User-Agent", "Mozilla/5.0"));
        return build(body, URL, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    record Film(String title, BigDecimal gross) { }

    static String build(String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode films;
        try {
            films = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("电影数据不是 JSON");
        }
        if (!films.isArray()) {
            throw DatasetHttp.invalid("电影数据不是数组");
        }
        List<Film> all = new ArrayList<>();
        for (JsonNode film : films) {
            String title = film.path("Title").asString("");
            JsonNode gross = film.path("Worldwide Gross");
            if (title.isBlank() || !gross.isNumber() || gross.asDouble() <= 0) continue;
            all.add(new Film(title,
                BigDecimal.valueOf(gross.asDouble() / SCALE).setScale(0, RoundingMode.HALF_UP)));
        }
        if (all.size() < 2) {
            throw DatasetHttp.invalid("电影有效条目不足两条");
        }
        all.sort(Comparator.comparing(Film::gross).reversed());
        int cap = Math.max(2, Math.min(limit, MAX_ROWS));
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Film film : all.subList(0, Math.min(cap, all.size()))) {
            rows.add(new DatasetFacts.Row(List.of(film.title(), film.gross())));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(SOURCE_KEY, "Vega Datasets / IMDb", sourceUrl,
            "worldwide_gross", "全球票房", "百万美元", 0, "annual", "rows",
            List.of("label", "value")), rows, retrievedAt);
    }
}
