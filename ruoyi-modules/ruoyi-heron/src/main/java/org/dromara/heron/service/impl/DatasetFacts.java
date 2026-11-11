package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * dataset/1 的 facts 与哈希唯一实现：适配器产出、data 闸重算，共用同一份口径。
 * 只做确定性归约，不含任何业务选择。
 */
public final class DatasetFacts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DatasetFacts() { }

    /** cells 只放 String 或 BigDecimal，顺序与 columns 一致。 */
    public record Row(List<Object> cells) { }

    public record Metric(String key, String unit, int precision) { }

    private record Point(String date, BigDecimal value) { }

    public static Map<String, Object> compute(String shape, List<Row> rows, int precision) {
        return switch (shape) {
            case "wide" -> wide(rows, precision);
            case "long" -> longFacts(rows, precision);
            case "rows" -> rowsFacts(rows, precision);
            case "geo" -> geoFacts(rows, precision);
            default -> throw new IllegalArgumentException("未知 shape: " + shape);
        };
    }

    public static String sha256(String shape, List<String> columns, Metric metric, List<Row> rows) {
        List<List<String>> cells = new ArrayList<>();
        for (Row row : rows) {
            List<String> line = new ArrayList<>();
            for (Object cell : row.cells()) {
                line.add(cell instanceof BigDecimal decimal
                    ? decimal.setScale(metric.precision(), RoundingMode.HALF_UP).toPlainString()
                    : String.valueOf(cell));
            }
            cells.add(line);
        }
        Map<String, Object> metricKeys = new TreeMap<>();
        metricKeys.put("key", metric.key());
        metricKeys.put("precision", metric.precision());
        metricKeys.put("unit", metric.unit());
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("columns", columns);
        canonical.put("metric", metricKeys);
        canonical.put("rows", cells);
        canonical.put("shape", shape);
        return DigestUtil.sha256Hex(JSON.writeValueAsString(canonical));
    }

    private static Map<String, Object> wide(List<Row> rows, int precision) {
        Point first = point(rows.get(0), precision);
        Point last = point(rows.get(rows.size() - 1), precision);
        Point min = first;
        Point max = first;
        for (Row row : rows) {
            Point point = point(row, precision);
            if (point.value().compareTo(min.value()) < 0) min = point;
            if (point.value().compareTo(max.value()) > 0) max = point;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("first", pointMap(first));
        facts.put("last", pointMap(last));
        facts.put("min", pointMap(min));
        facts.put("max", pointMap(max));
        facts.put("change", change(last, first, precision));
        facts.put("change_pct", pct(last, first));
        facts.put("period", period(first.date(), last.date()));
        return facts;
    }

    private static Map<String, Object> longFacts(List<Row> rows, int precision) {
        Map<String, List<Point>> series = new LinkedHashMap<>();
        String start = null;
        String end = null;
        for (Row row : rows) {
            String date = text(row, 0);
            String name = text(row, 1);
            series.computeIfAbsent(name, key -> new ArrayList<>())
                .add(new Point(date, number(row, 2, precision)));
            if (start == null || date.compareTo(start) < 0) start = date;
            if (end == null || date.compareTo(end) > 0) end = date;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> leader = null;
        double leaderValue = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, List<Point>> entry : series.entrySet()) {
            List<Point> points = entry.getValue();
            // 适配器按实体主序输出时，插入顺序就是时间序；这里仍然显式排序，避免依赖上游顺序
            points.sort(Comparator.comparing(Point::date));
            Point first = points.get(0);
            Point last = points.get(points.size() - 1);
            Point min = first;
            Point max = first;
            for (Point point : points) {
                if (point.value().compareTo(min.value()) < 0) min = point;
                if (point.value().compareTo(max.value()) > 0) max = point;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("first", first.value().doubleValue());
            item.put("last", last.value().doubleValue());
            item.put("min", min.value().doubleValue());
            item.put("max", max.value().doubleValue());
            item.put("change", change(last, first, precision));
            item.put("change_pct", pct(last, first));
            items.add(item);
            // 只有落在全局最后日期的系列才参与 leader；各国截止年份不一致时，否则是拿不同年份的数比大小
            if (end != null && end.equals(last.date()) && last.value().doubleValue() > leaderValue) {
                leaderValue = last.value().doubleValue();
                leader = Map.of("name", entry.getKey(), "value", leaderValue);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("series", items);
        facts.put("leader", leader);
        facts.put("period", period(start, end));
        return facts;
    }

    private static Map<String, Object> rowsFacts(List<Row> rows, int precision) {
        List<Map<String, Object>> items = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        Map<String, Object> leader = null;
        for (Row row : rows) {
            double value = number(row, 1, precision).doubleValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", text(row, 0));
            item.put("value", value);
            if (row.cells().size() > 2 && row.cells().get(2) != null) {
                item.put("change", number(row, 2, precision).doubleValue());
            }
            items.add(item);
            if (value < min) min = value;
            if (value > max) {
                max = value;
                leader = Map.of("label", text(row, 0), "value", value);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("items", items);
        facts.put("leader", leader);
        facts.put("count", rows.size());
        facts.put("spread", max - min);
        return facts;
    }

    private static Map<String, Object> geoFacts(List<Row> rows, int precision) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> leader = null;
        double leaderValue = Double.NEGATIVE_INFINITY;
        for (Row row : rows) {
            double value = number(row, 3, precision).doubleValue();
            String location = text(row, 0);
            items.add(Map.of("location", location, "value", value));
            if (value > leaderValue) {
                leaderValue = value;
                leader = Map.of("location", location, "value", value);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("items", items);
        facts.put("leader", leader);
        facts.put("count", rows.size());
        return facts;
    }

    private static Point point(Row row, int precision) {
        return new Point(text(row, 0), number(row, 1, precision));
    }

    private static Map<String, Object> pointMap(Point point) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("date", point.date());
        item.put("value", point.value().doubleValue());
        return item;
    }

    private static Map<String, Object> period(String start, String end) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("start_year", Integer.parseInt(start.substring(0, 4)));
        period.put("end_year", Integer.parseInt(end.substring(0, 4)));
        return period;
    }

    private static Double change(Point last, Point first, int precision) {
        return last.value().subtract(first.value()).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double pct(Point last, Point first) {
        if (first.value().signum() == 0) return null;
        return last.value().subtract(first.value()).multiply(BigDecimal.valueOf(100))
            .divide(first.value(), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String text(Row row, int index) {
        return (String) row.cells().get(index);
    }

    private static BigDecimal number(Row row, int index, int precision) {
        return ((BigDecimal) row.cells().get(index)).setScale(precision, RoundingMode.HALF_UP);
    }

    /** 按点路径取快照节点；占位符与闸共用同一份解析。 */
    static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node.isArray()) {
                if (!part.matches("\\d+")) return null;
                int index = Integer.parseInt(part);
                if (index >= node.size()) return null;
                node = node.get(index);
            } else if (node.isObject() && node.has(part)) {
                node = node.path(part);
            } else {
                return null;
            }
        }
        return node;
    }
}
