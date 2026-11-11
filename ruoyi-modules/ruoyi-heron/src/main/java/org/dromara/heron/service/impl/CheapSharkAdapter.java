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

/** CheapShark 游戏折扣 → dataset/1（rows：游戏与折扣幅度）；按商店分实体。 */
public final class CheapSharkAdapter implements DatasetAdapter {

    public record StoreSpec(String sourceKey, int storeId, String label) { }

    public static final List<StoreSpec> ALL = List.of(
        new StoreSpec("cheapshark-deals", 1, "Steam 折扣"),
        new StoreSpec("cheapshark-gog", 7, "GOG 折扣"),
        new StoreSpec("cheapshark-epic", 25, "Epic 折扣"));

    private static final String URL = "https://www.cheapshark.com/api/1.0/deals"
        + "?storeID=%d&sortBy=Deal%20Rating&pageSize=%d";
    /** CheapShark 拒绝通用 UA，必须带可识别的应用名与联系方式。 */
    private static final String USER_AGENT = "heron-news/0.1 (+https://github.com/zhoupei/heron)";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ROWS = 20;

    private final StoreSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public CheapSharkAdapter(StoreSpec spec) {
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
        String url = URL.formatted(spec.storeId(), cap);
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", USER_AGENT));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), cap);
    }

    record Deal(String title, BigDecimal savings) { }

    static String build(StoreSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode deals;
        try {
            deals = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("CheapShark 榜单不是 JSON");
        }
        if (!deals.isArray()) {
            throw DatasetHttp.invalid("CheapShark 榜单不是数组");
        }
        List<Deal> all = new ArrayList<>();
        for (JsonNode deal : deals) {
            String title = deal.path("title").asString("");
            try {
                BigDecimal savings = new BigDecimal(deal.path("savings").asString(""))
                    .setScale(1, RoundingMode.HALF_UP);
                if (!title.isBlank()) all.add(new Deal(title, savings));
            } catch (NumberFormatException e) {
                // 单条脏数据跳过，不拖垮整榜
            }
        }
        if (all.size() < 2) {
            throw DatasetHttp.invalid("CheapShark 有效条目不足两条");
        }
        all.sort(Comparator.comparing(Deal::savings).reversed());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Deal deal : all.subList(0, Math.min(limit, all.size()))) {
            rows.add(new DatasetFacts.Row(List.of(deal.title(), deal.savings())));
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "CheapShark", sourceUrl,
            "savings", spec.label(), "%", 1, "daily", "rows", List.of("label", "value")), rows, retrievedAt);
    }
}
