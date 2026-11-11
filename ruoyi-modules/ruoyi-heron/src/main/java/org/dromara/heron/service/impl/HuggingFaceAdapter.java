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

/** HuggingFace 趋势榜 → dataset/1（rows）；模型与数据集分实体。 */
public final class HuggingFaceAdapter implements DatasetAdapter {

    public record FeedSpec(String sourceKey, String kind, String label) { }

    public static final List<FeedSpec> ALL = List.of(
        new FeedSpec("huggingface-trending", "models", "模型下载量"),
        new FeedSpec("huggingface-datasets-trending", "datasets", "数据集下载量"));

    private static final String URL = "https://huggingface.co/api/%s"
        + "?sort=trendingScore&direction=-1&limit=%d";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final FeedSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public HuggingFaceAdapter(FeedSpec spec) {
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
        String url = URL.formatted(spec.kind(), cap);
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "heron-news/0.1"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Entry(String id, long downloads) { }

    static String build(FeedSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode items;
        try {
            items = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("HF 榜单不是 JSON");
        }
        if (!items.isArray()) {
            throw DatasetHttp.invalid("HF 榜单不是数组");
        }
        List<Entry> all = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("id").asString("");
            long downloads = item.path("downloads").asLong(-1);
            if (!id.isBlank() && downloads >= 0) all.add(new Entry(id, downloads));
        }
        if (all.size() < 2) {
            throw DatasetHttp.invalid("HF 有效条目不足两条");
        }
        all.sort(Comparator.comparingLong(Entry::downloads).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Entry entry : all.subList(0, Math.min(limit, all.size()))) {
            rows.add(new DatasetFacts.Row(List.of(entry.id(),
                BigDecimal.valueOf(entry.downloads()).setScale(0, RoundingMode.HALF_UP))));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "HuggingFace", sourceUrl,
            "downloads", spec.label(), "次", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
