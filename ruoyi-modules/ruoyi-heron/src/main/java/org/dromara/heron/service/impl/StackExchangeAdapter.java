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

/** Stack Exchange 热问 → dataset/1（rows：标题与得分）；按站点分实体。 */
public final class StackExchangeAdapter implements DatasetAdapter {

    public record SiteSpec(String sourceKey, String site, String label) { }

    public static final List<SiteSpec> ALL = List.of(
        new SiteSpec("stackexchange-hot", "stackoverflow", "Stack Overflow 热问"),
        new SiteSpec("stackexchange-superuser", "superuser", "Super User 热问"),
        new SiteSpec("stackexchange-serverfault", "serverfault", "Server Fault 热问"));

    private static final String URL = "https://api.stackexchange.com/2.3/questions"
        + "?order=desc&sort=hot&site=%s&pagesize=%d";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final SiteSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public StackExchangeAdapter(SiteSpec spec) {
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
        String url = URL.formatted(spec.site(), cap);
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "heron-news/0.1"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Question(String title, long score) { }

    static String build(SiteSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode items;
        try {
            items = JSON.readTree(body).path("items");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("StackExchange 响应不是 JSON");
        }
        if (!items.isArray()) {
            throw DatasetHttp.invalid("StackExchange 响应缺少 items");
        }
        List<Question> questions = new ArrayList<>();
        for (JsonNode item : items) {
            String title = item.path("title").asString("");
            long score = item.path("score").asLong(-1);
            if (!title.isBlank() && score >= 0) questions.add(new Question(title, score));
        }
        if (questions.size() < 2) {
            throw DatasetHttp.invalid("StackExchange 有效条目不足两条");
        }
        questions.sort(Comparator.comparingLong(Question::score).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Question question : questions.subList(0, Math.min(limit, questions.size()))) {
            rows.add(new DatasetFacts.Row(List.of(question.title(),
                BigDecimal.valueOf(question.score()).setScale(0, RoundingMode.HALF_UP))));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), spec.site(), sourceUrl, "score",
            spec.label(), "分", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
