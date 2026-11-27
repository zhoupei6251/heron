package org.dromara.heron.service.impl.shared.source;

import org.dromara.heron.service.impl.lane.databoard.DatasetAdapter;
import org.dromara.heron.service.impl.lane.databoard.DatasetFacts;
import org.dromara.heron.service.impl.lane.databoard.DatasetSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** USGS 地震目录 → dataset/1（geo：近 30 天每次地震的地点与震级，供三维地球模板）。 */
public final class UsgsQuakeGeoAdapter implements DatasetAdapter {

    public static final String SOURCE_KEY = "usgs-earthquakes-geo";

    private static final String URL = "https://earthquake.usgs.gov/fdsnws/event/1/query"
        + "?format=geojson&starttime=%s&endtime=%s&minmagnitude=4.5&orderby=time";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int WINDOW_DAYS = 30;

    private final HttpClient http = DatasetHttp.client();

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public String shape() {
        return "geo";
    }

    @Override
    public String fetch(int limit) {
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        int days = Math.max(2, Math.min(limit, WINDOW_DAYS));
        String url = URL.formatted(end.minusDays(days - 1L), end);
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "Mozilla/5.0"));
        return build(body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    public static String build(String body, String sourceUrl, Instant retrievedAt) {
        JsonNode features;
        try {
            features = JSON.readTree(body).path("features");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("地震目录不是 JSON");
        }
        if (!features.isArray() || features.isEmpty()) {
            throw DatasetHttp.invalid("地震目录没有可用事件");
        }
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            String place = properties.path("place").asString("");
            JsonNode mag = properties.path("mag");
            // 缺地点、缺震级或缺坐标的事件画不上地球，跳过而不是编一个值
            if (place.isBlank() || !mag.isNumber() || coordinates.size() < 2
                || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) {
                continue;
            }
            rows.add(new DatasetFacts.Row(List.of(place,
                BigDecimal.valueOf(coordinates.get(1).asDouble()),
                BigDecimal.valueOf(coordinates.get(0).asDouble()),
                BigDecimal.valueOf(mag.asDouble()))));
        }
        if (rows.isEmpty()) {
            throw DatasetHttp.invalid("地震目录没有带地点与坐标的事件");
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(SOURCE_KEY, "USGS", sourceUrl, "quake_mag",
            "全球 4.5 级以上地震分布", "级", 1, "daily", "geo",
            List.of("location", "latitude", "longitude", "value")), rows, retrievedAt);
    }
}
