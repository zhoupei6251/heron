package org.dromara.heron.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OWID 静态长序列 → dataset/1（wide，全球年度值）。
 * 年度日期统一写成 YYYY-01-01，满足契约的日期格式；模板会把这种日期显示成年份。
 */
public final class OwidSeriesAdapter implements DatasetAdapter {

    public record SeriesSpec(String sourceKey, String slug, String column, String label, String unit, double scale,
                             int precision, int fromYear) { }

    public static final List<SeriesSpec> ALL = List.of(
        new SeriesSpec("owid-life-expectancy-world", "life-expectancy", "Life expectancy",
            "全球预期寿命", "岁", 1, 1, 1950),
        new SeriesSpec("owid-population-world", "population", "Population",
            "全球人口", "十亿人", 1e9, 2, 1950),
        new SeriesSpec("owid-co2-world", "annual-co2-emissions-per-country", "Annual CO₂ emissions",
            "全球二氧化碳排放", "百万吨", 1e6, 0, 1950));

    private static final String URL = "https://ourworldindata.org/grapher/%s.csv";
    private static final int MAX_POINTS = 300;

    private final SeriesSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public OwidSeriesAdapter(SeriesSpec spec) {
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
        String url = URL.formatted(spec.slug());
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "Mozilla/5.0"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS), limit);
    }

    static String build(SeriesSpec spec, String csv, String sourceUrl, Instant retrievedAt, int limit) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            throw DatasetHttp.invalid("OWID CSV 为空");
        }
        List<String> header = fields(lines[0]);
        int column = header.indexOf(spec.column());
        if (column < 0) {
            throw DatasetHttp.invalid("OWID CSV 缺少列：" + spec.column());
        }
        TreeMap<Integer, BigDecimal> byYear = new TreeMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) continue;
            List<String> cells = fields(line);
            if (cells.size() <= Math.max(column, 2) || !"World".equals(cells.get(0))) continue;
            int year;
            try {
                year = Integer.parseInt(cells.get(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (year < spec.fromYear()) continue;
            try {
                BigDecimal value = new BigDecimal(cells.get(column))
                    .divide(BigDecimal.valueOf(spec.scale()), spec.precision(), RoundingMode.HALF_UP);
                byYear.put(year, value);
            } catch (NumberFormatException e) {
                // 空值或非数字行跳过，不拖垮整条序列
            }
        }
        if (byYear.size() < 2) {
            throw DatasetHttp.invalid("OWID 有效年份不足两年");
        }
        List<DatasetFacts.Row> all = new ArrayList<>();
        byYear.forEach((year, value) -> all.add(new DatasetFacts.Row(List.of(year + "-01-01", value))));
        int cap = Math.max(2, Math.min(limit, MAX_POINTS));
        int from = Math.max(0, all.size() - cap);
        List<DatasetFacts.Row> rows = all.subList(from, all.size());
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "Our World in Data", sourceUrl,
            spec.sourceKey(), spec.label(), spec.unit(), spec.precision(), "annual", "wide",
            List.of("date", "value")), rows, retrievedAt);
    }

    /** OWID 的列名含空格与 Unicode，用最简 CSV 解析：支持双引号包裹与转义。 */
    static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        out.add(field.toString());
        return out;
    }
}
