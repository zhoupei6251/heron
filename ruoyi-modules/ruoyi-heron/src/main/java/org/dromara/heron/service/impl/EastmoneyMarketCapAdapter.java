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

/**
 * 东财行情总市值 → dataset/1（rows）。
 * 美股 f20 是美元、港股是港币、A 股是人民币；统一按当日汇率折人民币，单位亿元。
 */
public final class EastmoneyMarketCapAdapter implements DatasetAdapter {

    public static final String SOURCE_KEY = "eastmoney-marketcap";
    private static final String SECIDS = String.join(",",
        "105.AAPL", "105.MSFT", "105.NVDA", "105.GOOGL", "105.AMZN", "105.META", "105.TSLA", "105.AVGO",
        "106.ORCL", "105.AMD",
        "116.00700", "116.09988", "116.03690", "116.01810", "116.09618", "116.09999",
        "0.300750", "0.002594", "1.688981", "1.601138");
    private static final String QUOTE = "https://push2.eastmoney.com/api/qt/ulist.np/get"
        + "?secids=" + SECIDS + "&fields=f12,f13,f14,f20";
    private static final String FX = "https://api.frankfurter.app/latest?from=%s&to=CNY";
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
        String quote = DatasetHttp.get(http, QUOTE, Map.of(
            "User-Agent", "Mozilla/5.0", "Referer", "https://quote.eastmoney.com/"));
        double usdCny = rate("USD");
        double hkdCny = rate("HKD");
        return build(quote, usdCny, hkdCny, QUOTE, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    private double rate(String from) {
        String body = DatasetHttp.get(http, FX.formatted(from), Map.of("User-Agent", "Mozilla/5.0"));
        JsonNode cny;
        try {
            cny = JSON.readTree(body).path("rates").path("CNY");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid(from + "/CNY 汇率不是 JSON");
        }
        if (!cny.isNumber() || cny.asDouble() <= 0) {
            throw DatasetHttp.invalid("缺少 " + from + "/CNY 汇率");
        }
        return cny.asDouble();
    }

    static String build(String quoteBody, double usdCny, double hkdCny, String sourceUrl, Instant retrievedAt,
                        int limit) {
        JsonNode rows;
        try {
            rows = JSON.readTree(quoteBody).path("data").path("diff");
        } catch (RuntimeException e) {
            throw DatasetHttp.invalid("市值榜单不是 JSON");
        }
        if (!rows.isArray() || rows.size() < 2) {
            throw DatasetHttp.invalid("市值榜单不足两家公司");
        }
        List<DatasetFacts.Row> values = new ArrayList<>();
        for (JsonNode row : rows) {
            String name = row.path("f14").asString("");
            JsonNode cap = row.path("f20");
            if (name.isBlank() || !cap.isNumber()) continue;
            double rate = switch (marketOf(row.path("f13").asInt(0))) {
                case "USD" -> usdCny;
                case "HKD" -> hkdCny;
                default -> 1;
            };
            BigDecimal cny = BigDecimal.valueOf(cap.asDouble() * rate)
                .divide(BigDecimal.valueOf(1e8), 0, RoundingMode.HALF_UP);
            values.add(new DatasetFacts.Row(List.of(name, cny)));
        }
        if (values.size() < 2) {
            throw DatasetHttp.invalid("市值有效条目不足两家");
        }
        values.sort(Comparator.comparing((DatasetFacts.Row row) -> (BigDecimal) row.cells().get(1)).reversed());
        List<DatasetFacts.Row> top = values.subList(0, Math.min(limit, values.size()));
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(SOURCE_KEY, "东方财富", sourceUrl,
            "market_cap", "总市值（折人民币）", "亿元", 0, "daily", "rows",
            List.of("label", "value")), top, retrievedAt);
    }

    /** f13 市场码：美股 105/106/107、港股 116、A 股 0/1；汇率只按市场取。 */
    static String marketOf(int market) {
        if (market == 105 || market == 106 || market == 107) return "USD";
        if (market == 116) return "HKD";
        return "CNY";
    }
}
