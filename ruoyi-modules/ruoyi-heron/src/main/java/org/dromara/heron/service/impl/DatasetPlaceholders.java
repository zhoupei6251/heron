package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把脚本里的 {{facts.*}} 换成可朗读、可上屏的文本。
 * 入库仍保留占位稿供 data 闸复核；TTS 与模板用这里解析后的副本。
 */
public final class DatasetPlaceholders {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DatasetPlaceholders() { }

    public static JsonNode resolve(JsonNode script, JsonNode dataset) {
        ObjectNode copy = (ObjectNode) JSON.readTree(script.toString());
        int precision = dataset.path("metric").path("precision").asInt(2);
        for (JsonNode scene : copy.path("scenes")) {
            ObjectNode slot = (ObjectNode) scene;
            slot.put("lead", fill(slot.path("lead").asString(""), dataset, precision));
            slot.put("body", fill(slot.path("body").asString(""), dataset, precision));
        }
        copy.put("closing", fill(copy.path("closing").asString(""), dataset, precision));
        copy.put("title", fill(copy.path("title").asString(""), dataset, precision));
        copy.put("source_name", fill(copy.path("source_name").asString(""), dataset, precision));
        return copy;
    }

    static String fill(String text, JsonNode dataset, int precision) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            JsonNode value = DatasetFacts.at(dataset, path);
            if (value == null || !(value.isNumber() || value.isString())) {
                throw new HeronBusinessException(409, "dataset_placeholder", "占位符无法解析 " + path);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(format(value, path, precision)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 百分比两位；年份与计数取整；其余按 metric.precision。 */
    private static String format(JsonNode value, String path, int precision) {
        if (value.isString()) return value.asString();
        double number = value.asDouble();
        if (path.endsWith("_year") || path.endsWith(".count")) {
            return Long.toString(Math.round(number));
        }
        int scale = path.endsWith("change_pct") ? 2 : precision;
        return BigDecimal.valueOf(number).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
