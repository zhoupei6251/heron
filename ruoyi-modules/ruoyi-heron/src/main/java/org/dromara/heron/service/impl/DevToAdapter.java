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

/** DEV Community 热门文章 → dataset/1（rows：标题与互动数）；可按标签分实体。 */
public final class DevToAdapter implements DatasetAdapter {

    public record TagSpec(String sourceKey, String tag, String label) { }

    public static final List<TagSpec> ALL = List.of(
        new TagSpec("devto-top", null, "DEV 热文榜"),
        new TagSpec("devto-ai", "ai", "DEV AI 热文"),
        new TagSpec("devto-javascript", "javascript", "DEV JavaScript 热文"));

    private static final String URL = "https://dev.to/api/articles?top=1&per_page=%d";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final TagSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public DevToAdapter(TagSpec spec) {
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
        String url = URL.formatted(cap) + (spec.tag() == null ? "" : "&tag=" + spec.tag());
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "heron-news/0.1"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Article(String title, long reactions) { }

    static String build(TagSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode articles;
        try {
            articles = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("DEV 榜单不是 JSON");
        }
        if (!articles.isArray()) {
            throw DatasetHttp.invalid("DEV 榜单不是数组");
        }
        List<Article> all = new ArrayList<>();
        for (JsonNode article : articles) {
            String title = article.path("title").asString("");
            long reactions = article.path("public_reactions_count").asLong(-1);
            if (!title.isBlank() && reactions >= 0) all.add(new Article(title, reactions));
        }
        if (all.size() < 2) {
            throw DatasetHttp.invalid("DEV 有效条目不足两条");
        }
        all.sort(Comparator.comparingLong(Article::reactions).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Article article : all.subList(0, Math.min(limit, all.size()))) {
            rows.add(new DatasetFacts.Row(List.of(article.title(),
                BigDecimal.valueOf(article.reactions()).setScale(0, RoundingMode.HALF_UP))));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "DEV Community", sourceUrl,
            "reactions", spec.label(), "次", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
