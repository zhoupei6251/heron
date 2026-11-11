package org.dromara.heron.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * OWID 国家年度数据 → dataset/1（long，赛跑矩阵）。
 * OWID 的 Entity 列混着 World、大洲、收入分组和已消亡国家，只保留带 ISO3 码的实体，否则任何 top-N 都会被聚合行占满。
 * 取数规则：窗口内历史峰值前 20 个实体，年份等距抽到 30 个时点，约 600 行。
 */
public final class OwidRaceAdapter implements DatasetAdapter {

    public record RaceSpec(String sourceKey, String slug, String column, String label, String unit,
                           double scale, int precision, int fromYear, int toYear) { }

    public static final List<RaceSpec> ALL = List.of(
        new RaceSpec("owid-population-race", "population", "Population",
            "各国人口赛跑", "亿人", 1e8, 2, 1950, 2023),
        new RaceSpec("owid-co2-race", "annual-co2-emissions-per-country", "Annual CO₂ emissions",
            "各国碳排放赛跑", "百万吨", 1e6, 0, 1950, 2023));
    // 预期寿命按峰值取前 20 全是摩纳哥、圣马力诺这类微型地区，选题讲不出东西，已从池里摘掉

    private static final String URL = "https://ourworldindata.org/grapher/%s.csv";
    private static final Pattern ISO3 = Pattern.compile("[A-Z]{3}");
    /** 港澳台在 OWID 里是独立实体，进主权国家排名会出问题，候选池里直接剔除。 */
    private static final Set<String> EXCLUDED_CODES = Set.of("HKG", "MAC", "TWN");
    private static final int ENTITIES = 20;
    private static final int POINTS = 30;
    private static final int MIN_YEARS = 20;

    private final RaceSpec spec;
    private final HttpClient http = DatasetHttp.client();

    public OwidRaceAdapter(RaceSpec spec) {
        this.spec = spec;
    }

    @Override
    public String sourceKey() {
        return spec.sourceKey();
    }

    @Override
    public String shape() {
        return "long";
    }

    @Override
    public String fetch(int limit) {
        String url = URL.formatted(spec.slug());
        String body = DatasetHttp.get(http, url, Map.of("User-Agent", "Mozilla/5.0"));
        return build(spec, body, url, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    static String build(RaceSpec spec, String csv, String sourceUrl, Instant retrievedAt) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            throw DatasetHttp.invalid("OWID CSV 为空");
        }
        List<String> header = OwidSeriesAdapter.fields(lines[0]);
        int column = header.indexOf(spec.column());
        int code = header.indexOf("Code");
        int year = header.indexOf("Year");
        int entity = header.indexOf("Entity");
        if (column < 0 || code < 0 || year < 0 || entity < 0) {
            throw DatasetHttp.invalid("OWID CSV 缺少列：" + spec.column());
        }
        Map<String, TreeMap<Integer, BigDecimal>> byEntity = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) continue;
            List<String> cells = OwidSeriesAdapter.fields(line);
            if (cells.size() <= Math.max(Math.max(column, code), Math.max(year, entity))) continue;
            if (!ISO3.matcher(cells.get(code)).matches()) continue;
            if (EXCLUDED_CODES.contains(cells.get(code))) continue;
            int yearValue;
            try {
                yearValue = Integer.parseInt(cells.get(year).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (yearValue < spec.fromYear() || yearValue > spec.toYear()) continue;
            try {
                BigDecimal value = new BigDecimal(cells.get(column).trim())
                    .divide(BigDecimal.valueOf(spec.scale()), spec.precision(), RoundingMode.HALF_UP);
                String name = CountryNames.of(cells.get(code), cells.get(entity));
                byEntity.computeIfAbsent(name, key -> new TreeMap<>()).put(yearValue, value);
            } catch (NumberFormatException e) {
                // 空值或非数字行跳过，不拖垮整条序列
            }
        }
        List<String> picked = byEntity.entrySet().stream()
            .filter(entry -> entry.getValue().size() >= MIN_YEARS)
            .sorted(Comparator.<Map.Entry<String, TreeMap<Integer, BigDecimal>>>comparingDouble(
                entry -> entry.getValue().lastEntry().getValue().doubleValue()).reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(ENTITIES)
            .map(Map.Entry::getKey)
            .toList();
        if (picked.size() < 2) {
            throw DatasetHttp.invalid("OWID 有效实体不足两个");
        }
        List<Integer> years = sampleYears(spec.fromYear(), spec.toYear());
        List<DatasetFacts.Row> rows = new ArrayList<>();
        for (int yearValue : years) {
            for (String name : picked) {
                BigDecimal value = byEntity.get(name).get(yearValue);
                if (value != null) {
                    rows.add(new DatasetFacts.Row(List.of(yearValue + "-01-01", name, value)));
                }
            }
        }
        if (rows.size() < 2) {
            throw DatasetHttp.invalid("OWID 抽稀后没有数据");
        }
        return DatasetSnapshot.build(new DatasetSnapshot.Spec(spec.sourceKey(), "Our World in Data", sourceUrl,
            spec.sourceKey(), spec.label(), spec.unit(), spec.precision(), "annual", "long",
            List.of("date", "series", "value")), rows, retrievedAt);
    }

    /** 等距抽稀，保证首尾都在；同年只留一个。 */
    static List<Integer> sampleYears(int from, int to) {
        List<Integer> years = new ArrayList<>();
        int span = Math.max(1, to - from);
        for (int index = 0; index < POINTS; index++) {
            int year = from + (int) Math.round((double) span * index / (POINTS - 1));
            if (years.isEmpty() || years.get(years.size() - 1) != year) years.add(year);
        }
        return years;
    }
}
