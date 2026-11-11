package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 维基百科昨日热读榜 → dataset/1（rows：文章与阅读量）；按语言分实体。 */
public final class WikimediaTopAdapter implements DatasetAdapter {

    public record WikiSpec(String sourceKey, String language, String label) { }

    public static final List<WikiSpec> ALL = List.of(
        new WikiSpec("wikimedia-zh-top", "zh", "中文维基昨日热读"),
        new WikiSpec("wikimedia-en-top", "en", "英文维基昨日热读"));

    private static final String URL = "https://wikimedia.org/api/rest_v1/metrics/pageviews/top"
        + "/%s.wikipedia/all-access/%d/%02d/%02d";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final WikiSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public WikimediaTopAdapter(WikiSpec spec) {
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
        // 用 UTC 昨日：当天的榜单尚未生成。
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        String url = URL.formatted(spec.language(), day.getYear(), day.getMonthValue(), day.getDayOfMonth());
        String body = DatasetHttp.get(http, url,
            Map.of("User-Agent", "heron-news/0.1 (+https://github.com/zhoupei/heron)"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(WikiSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode articles;
        try {
            articles = JSON.readTree(body).path("items").path(0).path("articles");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("维基榜单不是 JSON");
        }
        if (!articles.isArray()) {
            throw DatasetHttp.invalid("维基榜单缺少 articles");
        }
        int cap = Math.max(2, Math.min(limit, MAX_ROWS));
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (JsonNode article : articles) {
            if (rows.size() >= cap) break;
            String name = URLDecoder.decode(article.path("article").asString(""), StandardCharsets.UTF_8);
            JsonNode views = article.path("views");
            if (name.isBlank() || name.equals("Main_Page") || name.startsWith("Special:")
                || name.startsWith("Wikipedia:") || name.startsWith("Portal:") || !views.isNumber()) {
                continue;
            }
            rows.add(new DatasetFacts.Row(List.of(name,
                BigDecimal.valueOf(views.asLong()).setScale(0, RoundingMode.HALF_UP))));
        }
        if (rows.size() < 2) {
            throw DatasetHttp.invalid("维基榜单有效条目不足两条");
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "维基百科", sourceUrl, "views",
            spec.label(), "次", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
