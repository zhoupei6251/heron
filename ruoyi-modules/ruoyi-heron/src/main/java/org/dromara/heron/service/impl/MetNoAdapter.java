package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** MET Norway 定时预报 → dataset/1（wide，城市逐日最高气温）；一个城市一个 source_key。 */
public final class MetNoAdapter implements DatasetAdapter {

    public record CitySpec(String sourceKey, double lat, double lon, String city) { }

    public static final List<CitySpec> ALL = List.of(
        new CitySpec("metno-locationforecast", 39.9, 116.4, "北京"),
        new CitySpec("metno-shanghai", 31.2, 121.5, "上海"),
        new CitySpec("metno-guangzhou", 23.1, 113.3, "广州"),
        new CitySpec("metno-shenzhen", 22.5, 114.1, "深圳"),
        new CitySpec("metno-chengdu", 30.7, 104.1, "成都"),
        new CitySpec("metno-wuhan", 30.6, 114.3, "武汉"),
        new CitySpec("metno-xian", 34.3, 108.9, "西安"),
        new CitySpec("metno-hangzhou", 30.3, 120.2, "杭州"),
        new CitySpec("metno-nanjing", 32.1, 118.8, "南京"),
        new CitySpec("metno-chongqing", 29.6, 106.6, "重庆"),
        new CitySpec("metno-tianjin", 39.1, 117.2, "天津"),
        new CitySpec("metno-suzhou", 31.3, 120.6, "苏州"),
        new CitySpec("metno-changsha", 28.2, 112.9, "长沙"),
        new CitySpec("metno-zhengzhou", 34.7, 113.6, "郑州"),
        new CitySpec("metno-qingdao", 36.1, 120.4, "青岛"),
        new CitySpec("metno-shenyang", 41.8, 123.4, "沈阳"),
        new CitySpec("metno-harbin", 45.8, 126.5, "哈尔滨"),
        new CitySpec("metno-kunming", 25.0, 102.7, "昆明"),
        new CitySpec("metno-xiamen", 24.5, 118.1, "厦门"),
        new CitySpec("metno-urumqi", 43.8, 87.6, "乌鲁木齐"));

    private static final String URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.1f&lon=%.1f";
    /** 条款要求识别性 UA 与联系方式；用仓库地址，假 UA 会被封。 */
    private static final String USER_AGENT = "heron-news/0.1 (+https://github.com/zhoupei/heron)";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId LOCAL = ZoneId.of("Asia/Shanghai");
    private static final int PRECISION = 1;

    private final CitySpec spec;
    private final HttpClient http = DatasetHttp.client();

    public MetNoAdapter(CitySpec spec) {
        this.spec = spec;
    }

    @Override
    public String sourceKey() {
        return spec.sourceKey();
    }

    @Override
    public String shape() {
        return "wide";
    }

    @Override
    public String fetch(int limit) {
        String url = URL.formatted(spec.lat(), spec.lon());
        String body = DatasetHttp.get(http, url, Map.of(
            "User-Agent", USER_AGENT, "Accept", "application/json"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(CitySpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode series;
        try {
            series = JSON.readTree(body).path("properties").path("timeseries");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("天气响应不是 JSON");
        }
        if (!series.isArray()) {
            throw DatasetHttp.invalid("天气响应缺少 timeseries");
        }
        TreeMap<String, BigDecimal> dailyMax = new TreeMap<>();
        for (JsonNode item : series) {
            JsonNode temperature = item.path("data").path("instant").path("details").path("air_temperature");
            String time = item.path("time").asString("");
            if (!temperature.isNumber() || time.isBlank()) continue;
            String date;
            try {
                date = Instant.parse(time).atZone(LOCAL).toLocalDate().toString();
            } catch (RuntimeException e) {
                continue;
            }
            BigDecimal value = BigDecimal.valueOf(temperature.asDouble()).setScale(PRECISION, RoundingMode.HALF_UP);
            dailyMax.merge(date, value, (left, right) -> left.compareTo(right) >= 0 ? left : right);
        }
        if (dailyMax.size() < 2) {
            throw DatasetHttp.invalid("天气数据不足两天");
        }
        List<DatasetFacts.Row> all = new ArrayList<>();
        dailyMax.forEach((date, value) -> all.add(new DatasetFacts.Row(List.of(date, value))));
        int from = Math.max(0, all.size() - limit);
        List<DatasetFacts.Row> rows = all.subList(from, all.size());
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "MET Norway", sourceUrl,
            "temperature", spec.city() + "气温", "°C", PRECISION, "daily", "wide",
            List.of("date", "value")), rows, retrievedAt);
    }
}
