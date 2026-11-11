package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 东财指数日线 → dataset/1（wide）；一个实体一个 source_key。 */
public final class EastmoneyIndexAdapter implements DatasetAdapter {

    public record IndexSpec(String sourceKey, String secid, String label) { }

    /** 每个实体一条注册表记录，共同吃同一个适配器与同一份解析。 */
    public static final List<IndexSpec> ALL = List.of(
        new IndexSpec("eastmoney-sse", "1.000001", "上证指数"),
        new IndexSpec("eastmoney-szse", "0.399001", "深证成指"),
        new IndexSpec("eastmoney-chinext", "0.399006", "创业板指"),
        new IndexSpec("eastmoney-star50", "1.000688", "科创50"),
        new IndexSpec("eastmoney-csi300", "1.000300", "沪深300"),
        new IndexSpec("eastmoney-csi500", "1.000905", "中证500"),
        new IndexSpec("eastmoney-hsi", "100.HSI", "恒生指数"),
        new IndexSpec("eastmoney-ndx", "100.NDX", "纳斯达克"),
        new IndexSpec("eastmoney-spx", "100.SPX", "标普500"),
        new IndexSpec("eastmoney-djia", "100.DJIA", "道琼斯"),
        new IndexSpec("eastmoney-sse50", "1.000016", "上证50"),
        new IndexSpec("eastmoney-csi1000", "1.000852", "中证1000"),
        new IndexSpec("eastmoney-n225", "100.N225", "日经225"),
        new IndexSpec("eastmoney-semi", "1.512480", "半导体ETF"),
        new IndexSpec("eastmoney-tmt", "1.000998", "中证TMT"),
        new IndexSpec("eastmoney-nev", "0.399976", "CS新能车"),
        new IndexSpec("eastmoney-kospi", "100.KS11", "韩国KOSPI"),
        new IndexSpec("eastmoney-asx200", "100.AS51", "澳大利亚标普200"),
        new IndexSpec("eastmoney-dax", "100.GDAXI", "德国DAX30"),
        new IndexSpec("eastmoney-sensex", "100.SENSEX", "印度孟买SENSEX"));

    private static final String URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
        + "?secid=%s&fields1=f1,f2,f3,f4&fields2=f51,f52,f53&klt=101&fqt=1&lmt=%d&end=20500101";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PRECISION = 2;
    private static final int MAX_POINTS = 2000;

    private final IndexSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public EastmoneyIndexAdapter(IndexSpec spec) {
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
        String url = URL.formatted(spec.secid(), Math.max(2, Math.min(limit, MAX_POINTS)));
        String body = DatasetHttp.get(http, url, Map.of(
            "User-Agent", "Mozilla/5.0", "Referer", "https://quote.eastmoney.com/"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(IndexSpec spec, String body, String sourceUrl, Instant retrievedAt, int limit) {
        JsonNode klines;
        try {
            klines = JSON.readTree(body).path("data").path("klines");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("指数日线不是 JSON");
        }
        if (!klines.isArray() || klines.size() < 2) {
            throw DatasetHttp.invalid("指数日线不足两行");
        }
        List<DatasetFacts.Row> all = new ArrayList<>();
        for (JsonNode item : klines) {
            // fields2=f51,f52,f53 → "日期,开盘,收盘"
            String[] parts = item.asString("").split(",");
            if (parts.length < 3 || !parts[0].matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw DatasetHttp.invalid("指数日线行格式不合法");
            }
            BigDecimal close;
            try {
                close = new BigDecimal(parts[2]).setScale(PRECISION, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                throw DatasetHttp.invalid("指数收盘价不是数字：" + parts[0]);
            }
            all.add(new DatasetFacts.Row(List.of(parts[0], close)));
        }
        int from = Math.max(0, all.size() - limit);
        List<DatasetFacts.Row> rows = all.subList(from, all.size());
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "东方财富", sourceUrl, "close",
            spec.label() + "收盘", "点", PRECISION, "daily", "wide", List.of("date", "value")), rows, retrievedAt);
    }
}
