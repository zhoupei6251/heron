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

/** Hacker News 热帖 → dataset/1（rows：标题与得分，供排行模板）。 */
public final class HackerNewsAdapter implements DatasetAdapter {

    public static final String SOURCE_KEY = "hackernews-top";
    private static final String TOP = "https://hacker-news.firebaseio.com/v0/topstories.json";
    private static final String ITEM = "https://hacker-news.firebaseio.com/v0/item/%d.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
        int cap = Math.max(2, Math.min(limit, MAX_ROWS));
        JsonNode ids;
        try {
            ids = JSON.readTree(DatasetHttp.get(http, TOP, Map.of("User-Agent", "Mozilla/5.0")));
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("HN 榜单不是 JSON");
        }
        if (!ids.isArray()) {
            throw DatasetHttp.invalid("HN 榜单缺少 id 数组");
        }
        List<String> items = new ArrayList<>();
        for (int index = 0; index < Math.min(ids.size(), cap); index++) {
            long id = ids.get(index).asLong(-1);
            if (id <= 0) continue;
            items.add(DatasetHttp.get(http, ITEM.formatted(id), Map.of("User-Agent", "Mozilla/5.0")));
        }
        return build(items, TOP, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Story(String title, long score) { }

    static String build(List<String> itemJson, String sourceUrl, Instant retrievedAt, int limit) {
        List<Story> stories = new ArrayList<>();
        for (String json : itemJson) {
            JsonNode node;
            try {
                node = JSON.readTree(json);
            } catch (RuntimeException e) {
                continue;
            }
            String title = node.path("title").asString("");
            long score = node.path("score").asLong(-1);
            if (!title.isBlank() && score >= 0) stories.add(new Story(title, score));
        }
        if (stories.size() < 2) {
            throw DatasetHttp.invalid("HN 有效条目不足两条");
        }
        stories.sort(Comparator.comparingLong(Story::score).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Story story : stories.subList(0, Math.min(limit, stories.size()))) {
            rows.add(new DatasetFacts.Row(List.of(story.title(),
                BigDecimal.valueOf(story.score()).setScale(0, RoundingMode.HALF_UP))));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(SOURCE_KEY, "Hacker News", sourceUrl, "score",
            "热帖得分", "分", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
