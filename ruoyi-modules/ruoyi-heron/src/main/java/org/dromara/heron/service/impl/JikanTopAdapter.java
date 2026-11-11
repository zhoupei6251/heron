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

/** Jikan（MyAnimeList 非官方镜像）番剧/漫画榜 → dataset/1（rows）。 */
public final class JikanTopAdapter implements DatasetAdapter {

    public record ListSpec(String sourceKey, String path, String label) { }

    public static final List<ListSpec> ALL = List.of(
        new ListSpec("jikan-top-anime", "anime", "番剧追番人数"),
        new ListSpec("jikan-top-manga", "manga", "漫画收藏人数"));

    private static final String URL = "https://api.jikan.moe/v4/top/%s?limit=%d";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final ListSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public JikanTopAdapter(ListSpec spec) {
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
        String url = URL.formatted(spec.path(), cap);
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "Mozilla/5.0"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Entry(String title, long members) { }

    static String build(ListSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode data;
        try {
            data = JSON.readTree(body).path("data");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("Jikan 榜单不是 JSON");
        }
        if (!data.isArray()) {
            throw DatasetHttp.invalid("Jikan 榜单缺少 data");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode item : data) {
            String title = item.path("title").asString("");
            long members = item.path("members").asLong(-1);
            if (!title.isBlank() && members >= 0) entries.add(new Entry(title, members));
        }
        if (entries.size() < 2) {
            throw DatasetHttp.invalid("Jikan 有效条目不足两条");
        }
        entries.sort(Comparator.comparingLong(Entry::members).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Entry entry : entries.subList(0, Math.min(limit, entries.size()))) {
            rows.add(new DatasetFacts.Row(List.of(entry.title(),
                BigDecimal.valueOf(entry.members()).setScale(0, RoundingMode.HALF_UP))));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "MyAnimeList", sourceUrl, "members",
            spec.label(), "人", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
