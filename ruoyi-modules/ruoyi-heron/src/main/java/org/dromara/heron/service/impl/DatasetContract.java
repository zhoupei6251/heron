package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dataset/1 契约闸：判快照自身一致、facts 重算、哈希复现，以及旁白占位符。
 * 不判事实真伪，也不改快照——失败只列条目，由调用方退回。
 */
public final class DatasetContract {

    private static final Set<String> REQUIRED = Set.of("contract", "source_key", "source_name", "source_url",
        "retrieved_at", "http_status", "adapter_version", "license", "metric", "cadence", "shape", "columns",
        "rows", "facts", "data_sha256");
    private static final Set<String> SHAPES = Set.of("wide", "long", "rows", "geo");
    private static final Set<String> CADENCES = Set.of("realtime", "daily", "monthly", "annual", "mixed");
    private static final Set<String> COMMERCIAL = Set.of("true", "false", "unknown");
    private static final List<String> WIDE = List.of("date", "value");
    private static final List<String> LONG = List.of("date", "series", "value");
    private static final List<String> ROWS = List.of("label", "value");
    private static final List<String> ROWS_CHANGE = List.of("label", "value", "change");
    private static final List<String> GEO = List.of("location", "latitude", "longitude", "value");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SECRET = Pattern.compile(
        "[?&](key|token|secret|apikey|api_key|access_token)=", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    private DatasetContract() { }

    /** enabledKeys 来自注册表；闸不查库，只认调用方给的启用集合。 */
    public static List<String> violations(JsonNode snapshot, Set<String> enabledKeys) {
        if (snapshot == null || !snapshot.isObject()) {
            return List.of("dataset 不是 JSON 对象");
        }
        List<String> items = new ArrayList<>();
        if (!"dataset/1".equals(snapshot.path("contract").asString(""))) {
            items.add("contract 不是 dataset/1");
        }
        for (String field : REQUIRED) {
            if (!snapshot.has(field)) items.add("缺字段 " + field);
        }
        Set<String> allowed = new HashSet<>(REQUIRED);
        allowed.add("time_range");
        for (String field : snapshot.propertyNames()) {
            if (!allowed.contains(field)) items.add("未知字段 " + field);
        }

        text(items, snapshot, "source_key", 64);
        text(items, snapshot, "source_name", 100);
        text(items, snapshot, "adapter_version", 16);
        String sourceKey = snapshot.path("source_key").asString("");
        if (enabledKeys == null || !enabledKeys.contains(sourceKey)) {
            items.add("source_key 未在注册表启用: " + sourceKey);
        }
        if (!snapshot.path("retrieved_at").asString("").isBlank()) {
            try {
                Instant.parse(snapshot.path("retrieved_at").asString());
            } catch (RuntimeException e) {
                items.add("retrieved_at 不是 ISO-8601 时刻");
            }
        }
        if (snapshot.path("http_status").asInt(0) != 200) items.add("http_status 不是 200");
        String sourceUrl = snapshot.path("source_url").asString("");
        if (!sourceUrl.matches("https?://[^\\s]{1,2048}")) items.add("source_url 不是 http(s) 链接");
        else if (SECRET.matcher(sourceUrl).find()) items.add("source_url 含 key/token 查询参数");

        String shape = snapshot.path("shape").asString("");
        if (!SHAPES.contains(shape)) items.add("未知 shape: " + shape);
        if (!CADENCES.contains(snapshot.path("cadence").asString(""))) {
            items.add("未知 cadence: " + snapshot.path("cadence").asString(""));
        }
        JsonNode license = snapshot.path("license");
        if (!license.isObject()) {
            items.add("license 不是对象");
        } else {
            if (!COMMERCIAL.contains(license.path("commercial_ok").asString(""))) {
                items.add("license.commercial_ok 只能是 true/false/unknown");
            }
            if (license.path("attribution").asString("").isBlank()) items.add("license.attribution 为空");
        }

        int precision = precision(items, snapshot.path("metric"));
        List<String> columns = columns(items, snapshot.path("columns"), shape);
        JsonNode rows = snapshot.path("rows");
        boolean rowsOk = rows(items, rows, columns, shape);
        if (rowsOk && !columns.isEmpty() && precision >= 0) {
            timeRange(items, snapshot.path("time_range"), rows, shape);
            JsonNode facts = snapshot.path("facts");
            if (!facts.isObject()) {
                items.add("facts 不是对象");
            } else {
                List<DatasetFacts.Row> datasetRows = datasetRows(rows);
                try {
                    Map<String, Object> expected = DatasetFacts.compute(shape, datasetRows, precision);
                    compare(items, "facts", facts, expected, precision);
                    String hash = DatasetFacts.sha256(shape, columns,
                        new DatasetFacts.Metric(snapshot.path("metric").path("key").asString(""), unit(snapshot), precision),
                        datasetRows);
                    if (!hash.equals(snapshot.path("data_sha256").asString(""))) {
                        items.add("data_sha256 与重算不一致");
                    }
                } catch (RuntimeException e) {
                    items.add("facts 重算失败: " + e.getMessage());
                }
            }
        }
        if (!snapshot.path("data_sha256").asString("").matches("[0-9a-f]{64}")) {
            items.add("data_sha256 不是完整 SHA-256");
        }
        return List.copyOf(new LinkedHashSet<>(items));
    }

    /** 旁白只许引用 facts 标量，正文不许出现任何阿拉伯数字。 */
    public static List<String> narrationViolations(JsonNode snapshot, String text) {
        if (text == null || text.isBlank()) return List.of("旁白为空");
        List<String> items = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder plain = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            plain.append(text, last, matcher.start());
            String path = matcher.group(1);
            if (!resolves(snapshot, path)) items.add("占位符无法解析 " + path);
            last = matcher.end();
        }
        plain.append(text.substring(last));
        if (plain.toString().matches("(?s).*[0-9].*")) items.add("旁白正文含裸数字");
        return List.copyOf(items);
    }

    private static int precision(List<String> items, JsonNode metric) {
        if (!metric.isObject()) {
            items.add("metric 不是对象");
            return -1;
        }
        text(items, metric, "key", 40);
        text(items, metric, "label", 60);
        text(items, metric, "unit", 40);
        int precision = metric.path("precision").asInt(-1);
        if (precision < 0 || precision > 4) {
            items.add("metric.precision 不在 0–4");
            return -1;
        }
        return precision;
    }

    private static String unit(JsonNode snapshot) {
        return snapshot.path("metric").path("unit").asString("");
    }

    private static List<String> columns(List<String> items, JsonNode node, String shape) {
        if (!node.isArray()) {
            items.add("columns 不是数组");
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (JsonNode cell : node) columns.add(cell.asString(""));
        boolean matched = switch (shape) {
            case "wide" -> columns.equals(WIDE);
            case "long" -> columns.equals(LONG);
            case "rows" -> columns.equals(ROWS) || columns.equals(ROWS_CHANGE);
            case "geo" -> columns.equals(GEO);
            default -> false;
        };
        if (!matched) items.add("columns 与 shape " + shape + " 不匹配: " + columns);
        return columns;
    }

    private static boolean rows(List<String> items, JsonNode rows, List<String> columns, String shape) {
        if (!rows.isArray()) {
            items.add("rows 不是数组");
            return false;
        }
        if (rows.size() < (timeShape(shape) ? 2 : 1)) {
            items.add("rows 行数不足");
            return false;
        }
        boolean ok = true;
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (!row.isArray() || row.size() != columns.size()) {
                items.add("第 " + (index + 1) + " 行列数与 columns 不一致");
                ok = false;
                continue;
            }
            for (int cell = 0; cell < row.size(); cell++) {
                JsonNode value = row.get(cell);
                if (numeric(shape, cell)) {
                    if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                        items.add("第 " + (index + 1) + " 行第 " + (cell + 1) + " 列不是有限数字");
                        ok = false;
                    }
                } else if (!value.isString() || value.asString().isBlank()) {
                    items.add("第 " + (index + 1) + " 行第 " + (cell + 1) + " 列不是非空字符串");
                    ok = false;
                }
            }
            if (timeShape(shape) && row.size() > 0 && !DATE.matcher(row.get(0).asString("")).matches()) {
                items.add("第 " + (index + 1) + " 行日期不合法");
                ok = false;
            }
        }
        if (timeShape(shape)) {
            String previous = "";
            for (JsonNode row : rows) {
                String date = row.get(0).asString("");
                if (date.compareTo(previous) <= 0) {
                    items.add("日期未严格递增: " + previous + " → " + date);
                    ok = false;
                    break;
                }
                previous = date;
            }
        }
        return ok;
    }

    private static void timeRange(List<String> items, JsonNode range, JsonNode rows, String shape) {
        if (!timeShape(shape)) {
            if (range.isObject()) items.add("非时间形态不应有 time_range");
            return;
        }
        if (!range.isObject()) {
            items.add("time_range 不是对象");
            return;
        }
        String start = rows.get(0).get(0).asString("");
        String end = rows.get(rows.size() - 1).get(0).asString("");
        if (!start.equals(range.path("start").asString(""))) items.add("time_range.start 与首行不一致");
        if (!end.equals(range.path("end").asString(""))) items.add("time_range.end 与末行不一致");
        if (range.path("points").asInt(-1) != rows.size()) items.add("time_range.points 与行数不一致");
    }

    private static boolean timeShape(String shape) {
        return "wide".equals(shape) || "long".equals(shape);
    }

    private static boolean numeric(String shape, int index) {
        return switch (shape) {
            case "wide" -> index == 1;
            case "long" -> index == 2;
            case "rows", "geo" -> index >= 1;
            default -> false;
        };
    }

    private static List<DatasetFacts.Row> datasetRows(JsonNode rows) {
        List<DatasetFacts.Row> result = new ArrayList<>();
        for (JsonNode row : rows) {
            List<Object> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.isNumber() ? new BigDecimal(cell.asString()) : cell.asString(""));
            }
            result.add(new DatasetFacts.Row(cells));
        }
        return result;
    }

    private static void compare(List<String> items, String path, JsonNode actual, Object expected, int precision) {
        if (expected == null) {
            if (!actual.isNull()) items.add(path + " 应为 null");
            return;
        }
        if (expected instanceof Map<?, ?> map) {
            if (!actual.isObject()) {
                items.add(path + " 不是对象");
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!actual.has(key)) items.add(path + "." + key + " 缺失");
                else compare(items, path + "." + key, actual.path(key), entry.getValue(), precision);
            }
            for (String key : actual.propertyNames()) {
                if (!map.containsKey(key)) items.add(path + "." + key + " 多余");
            }
            return;
        }
        if (expected instanceof List<?> list) {
            if (!actual.isArray() || actual.size() != list.size()) {
                items.add(path + " 长度不符");
                return;
            }
            for (int index = 0; index < list.size(); index++) {
                compare(items, path + "[" + index + "]", actual.get(index), list.get(index), precision);
            }
            return;
        }
        if (expected instanceof Number number) {
            int scale = path.endsWith("change_pct") ? 2 : precision;
            BigDecimal want = BigDecimal.valueOf(number.doubleValue()).setScale(scale, RoundingMode.HALF_UP);
            if (!actual.isNumber()
                || !new BigDecimal(actual.asString()).setScale(scale, RoundingMode.HALF_UP).equals(want)) {
                items.add(path + " 与重算不一致");
            }
            return;
        }
        if (expected instanceof String text) {
            if (!actual.isString() || !text.equals(actual.asString())) items.add(path + " 与重算不一致");
            return;
        }
        items.add(path + " 类型无法比较");
    }

    private static boolean resolves(JsonNode snapshot, String path) {
        if (!path.startsWith("facts.")) return false;
        JsonNode node = DatasetFacts.at(snapshot, path);
        return node != null && (node.isNumber() || node.isString());
    }

    private static void text(List<String> items, JsonNode node, String field, int maxLength) {
        if (!node.has(field)) return;
        String value = node.path(field).asString("");
        if (value.isBlank()) items.add(field + " 为空");
        else if (value.length() > maxLength) items.add(field + " 超过 " + maxLength + " 字符");
    }
}
