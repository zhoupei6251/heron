package org.dromara.heron.service.impl;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dataset/1 的唯一组装入口：适配器只负责抓取与解析，契约字段、授权、facts 与哈希都在这里。
 */
public final class DatasetSnapshot {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DatasetSnapshot() { }

    public record Spec(String sourceKey, String sourceName, String sourceUrl, String metricKey, String metricLabel,
                       String unit, int precision, String cadence, String shape, List<String> columns) { }

    public static String build(Spec spec, List<DatasetFacts.Row> rows, Instant retrievedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contract", "dataset/1");
        snapshot.put("source_key", spec.sourceKey());
        snapshot.put("source_name", spec.sourceName());
        snapshot.put("source_url", spec.sourceUrl());
        snapshot.put("retrieved_at", retrievedAt.toString());
        snapshot.put("http_status", 200);
        snapshot.put("adapter_version", "1");
        snapshot.put("license", license(spec.sourceName()));
        snapshot.put("metric", metric(spec));
        snapshot.put("cadence", spec.cadence());
        snapshot.put("shape", spec.shape());
        snapshot.put("columns", spec.columns());
        snapshot.put("rows", rows.stream().map(DatasetFacts.Row::cells).toList());
        if (timeShape(spec.shape())) {
            snapshot.put("time_range", timeRange(rows));
        }
        snapshot.put("facts", DatasetFacts.compute(spec.shape(), rows, spec.precision()));
        snapshot.put("data_sha256", DatasetFacts.sha256(spec.shape(), spec.columns(),
            new DatasetFacts.Metric(spec.metricKey(), spec.unit(), spec.precision()), rows));
        return JSON.writeValueAsString(snapshot);
    }

    private static boolean timeShape(String shape) {
        return "wide".equals(shape) || "long".equals(shape);
    }

    private static Map<String, Object> timeRange(List<DatasetFacts.Row> rows) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", String.valueOf(rows.get(0).cells().get(0)));
        range.put("end", String.valueOf(rows.get(rows.size() - 1).cells().get(0)));
        range.put("points", rows.size());
        return range;
    }

    private static Map<String, Object> license(String sourceName) {
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("name", "unknown");
        license.put("commercial_ok", "unknown");
        license.put("terms_url", null);
        license.put("attribution", "来源：" + sourceName);
        return license;
    }

    private static Map<String, Object> metric(Spec spec) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("key", spec.metricKey());
        metric.put("label", spec.metricLabel());
        metric.put("unit", spec.unit());
        metric.put("precision", spec.precision());
        return metric;
    }
}
