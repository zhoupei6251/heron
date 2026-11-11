package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 新浪外盘期货日线 → dataset/1（wide）。
 * 只取日线：日线已含当日形成的 bar，实时报价对日频图表没有增量信息。
 */
public final class SinaFuturesDatasetAdapter implements DatasetAdapter {

    public static final Series GOLD = new Series("sina-gold-xau", "新浪财经", "close", "伦敦金收盘价", "USD/oz",
        "https://stock.finance.sina.com.cn/futures/api/jsonp.php/var%20_hf_XAU=/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=XAU");
    public static final Series OIL = new Series("sina-oil-cl", "新浪财经", "close", "WTI 原油收盘价", "USD/bbl",
        "https://stock.finance.sina.com.cn/futures/api/jsonp.php/var%20_hf_CL=/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=CL");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MIN_POINTS = 2;
    private static final int MAX_POINTS = 2000;
    private static final int PRECISION = 2;

    private final Series series;
    private final HttpClient http;

    public SinaFuturesDatasetAdapter(Series series) {
        this(series, DatasetHttp.client());
    }

    SinaFuturesDatasetAdapter(Series series, HttpClient http) {
        this.series = series;
        this.http = http;
    }

    public record Series(String sourceKey, String sourceName, String metricKey, String metricLabel, String unit,
                         String endpoint) { }

    private record Point(String date, BigDecimal value) { }

    @Override
    public String sourceKey() {
        return series.sourceKey();
    }

    @Override
    public String shape() {
        return "wide";
    }

    /** 抓一次日线并产出快照；失败不重试，交给调用方决定是否重跑。 */
    @Override
    public String fetch(int limit) {
        int points = Math.max(MIN_POINTS, Math.min(limit, MAX_POINTS));
        HttpRequest request = HttpRequest.newBuilder(URI.create(series.endpoint()))
            .timeout(TIMEOUT)
            .header("Referer", "https://finance.sina.com.cn")
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw invalid("日线接口返回 HTTP " + response.statusCode());
            }
            if (response.body().length > MAX_BYTES) {
                throw invalid("日线响应超过 " + MAX_BYTES + " 字节");
            }
            // 新浪行情是 GBK；中文只出现在实时快照，解码用 GBK 对纯数字日线同样安全。
            String body = new String(response.body(), Charset.forName("GBK"));
            return build(series, body, Instant.now().truncatedTo(ChronoUnit.SECONDS), points);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw invalid("日线抓取被中断");
        } catch (java.io.IOException e) {
            throw invalid("日线抓取失败：" + e.getMessage());
        }
    }

    /** 纯解析与组装，测试不碰网络。 */
    static String build(Series series, String body, Instant retrievedAt, int limit) {
        List<Point> all = parse(body);
        int from = Math.max(0, all.size() - limit);
        List<Point> points = all.subList(from, all.size());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (Point point : points) {
            rows.add(new DatasetFacts.Row(List.of(point.date(), point.value())));
        }
        DatasetSnapshot.Spec spec = new DatasetSnapshot.Spec(series.sourceKey(), series.sourceName(),
            series.endpoint(), series.metricKey(), series.metricLabel(), series.unit(), PRECISION,
            "daily", "wide", List.of("date", "value"));
        return DatasetSnapshot.build(spec, rows, retrievedAt);
    }

    private static List<Point> parse(String body) {
        int start = body.indexOf('[');
        int end = body.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw invalid("响应里找不到日线数组");
        }
        JsonNode array;
        try {
            array = JSON.readTree(body.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw invalid("日线 JSON 解析失败");
        }
        if (!array.isArray() || array.size() < MIN_POINTS) {
            throw invalid("日线数据不足 " + MIN_POINTS + " 行");
        }
        // 二十年日线里偶发重复日期不应让整次抓取失败：按日期排序去重，同日以后出现的值为准。
        Map<String, BigDecimal> byDate = new TreeMap<>();
        for (JsonNode item : array) {
            String date = item.path("date").asString("");
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw invalid("日线日期不合法：" + date);
            }
            BigDecimal value;
            try {
                value = new BigDecimal(item.path("close").asString(""));
            } catch (NumberFormatException e) {
                throw invalid("收盘价不是数字：" + date);
            }
            byDate.put(date, value.setScale(PRECISION, RoundingMode.HALF_UP));
        }
        List<Point> points = new ArrayList<>();
        byDate.forEach((date, value) -> points.add(new Point(date, value)));
        return points;
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(502, "dataset_invalid", reason);
    }
}
