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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** GitHub 近 30 天新建、按 star 排序的仓库 → dataset/1（rows）；可按语言分实体。 */
public final class GithubTrendingAdapter implements DatasetAdapter {

    public record TrendSpec(String sourceKey, String language, String label) { }

    public static final List<TrendSpec> ALL = List.of(
        new TrendSpec("github-trending", null, "近 30 天星标"),
        new TrendSpec("github-trending-python", "python", "Python 新晋星标"),
        new TrendSpec("github-trending-typescript", "typescript", "TypeScript 新晋星标"),
        new TrendSpec("github-trending-rust", "rust", "Rust 新晋星标"));

    private static final String URL = "https://api.github.com/search/repositories"
        + "?q=%s&sort=stars&order=desc&per_page=%d";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final TrendSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public GithubTrendingAdapter(TrendSpec spec) {
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
        String query = "created:%3E" + LocalDate.now(ZoneOffset.UTC).minusDays(30);
        if (spec.language() != null) {
            query += "+language:" + spec.language();
        }
        String url = URL.formatted(query, cap);
        String body = DatasetHttp.get(http, url, Map.of(
            "User-Agent", "heron-news/0.1",
            "Accept", "application/vnd.github+json"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Repo(String name, long stars) { }

    static String build(TrendSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode items;
        try {
            items = JSON.readTree(body).path("items");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("GitHub 搜索不是 JSON");
        }
        if (!items.isArray()) {
            throw DatasetHttp.invalid("GitHub 搜索缺少 items");
        }
        List<Repo> repos = new ArrayList<>();
        for (JsonNode item : items) {
            String name = item.path("full_name").asString("");
            long stars = item.path("stargazers_count").asLong(-1);
            if (!name.isBlank() && stars >= 0) repos.add(new Repo(name, stars));
        }
        if (repos.size() < 2) {
            throw DatasetHttp.invalid("GitHub 有效条目不足两条");
        }
        repos.sort(Comparator.comparingLong(Repo::stars).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Repo repo : repos.subList(0, Math.min(limit, repos.size()))) {
            rows.add(new DatasetFacts.Row(List.of(repo.name(),
                BigDecimal.valueOf(repo.stars()).setScale(0, RoundingMode.HALF_UP))));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "GitHub", sourceUrl, "stars",
            spec.label(), "星", 0, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
