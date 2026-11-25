package org.dromara.heron.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从一条爆款拆解中归纳出的技法卡。 */
public record TrendTechniqueCard(
    String hookType,
    double firstShotSeconds,
    double avgShotSeconds,
    String cutRhythm,
    String textStyle,
    List<String> motionTypes,
    String infoDensity,
    List<String> reusablePoints,
    String heronTemplateFit) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static TrendTechniqueCard parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("技法卡输出为空");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("技法卡不是 JSON 对象");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("技法卡 JSON 无法解析", e);
        }
        double first = number(root, "firstShotSeconds");
        double average = number(root, "avgShotSeconds");
        if (first < 0 || average <= 0) {
            throw new IllegalArgumentException("firstShotSeconds 必须非负，avgShotSeconds 必须大于 0");
        }
        return new TrendTechniqueCard(
            text(root, "hookType", 120),
            first,
            average,
            text(root, "cutRhythm", 200),
            text(root, "textStyle", 200),
            strings(root, "motionTypes", 20, 120),
            text(root, "infoDensity", 120),
            strings(root, "reusablePoints", 20, 500),
            text(root, "heronTemplateFit", 1000));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("hookType", hookType);
        values.put("firstShotSeconds", firstShotSeconds);
        values.put("avgShotSeconds", avgShotSeconds);
        values.put("cutRhythm", cutRhythm);
        values.put("textStyle", textStyle);
        values.put("motionTypes", motionTypes);
        values.put("infoDensity", infoDensity);
        values.put("reusablePoints", reusablePoints);
        values.put("heronTemplateFit", heronTemplateFit);
        return values;
    }

    public String json() {
        return JSON.writeValueAsString(toMap());
    }

    private static String text(JsonNode root, String field, int max) {
        JsonNode value = root.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("技法卡缺少 " + field);
        }
        String text = value.asString().strip();
        if (text.length() > max) {
            throw new IllegalArgumentException("技法卡字段 " + field + " 超过 " + max + " 字");
        }
        return text;
    }

    private static double number(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException("技法卡缺少合法数字 " + field);
        }
        return value.asDouble();
    }

    private static List<String> strings(JsonNode root, String field, int maxItems, int maxLength) {
        JsonNode value = root.path(field);
        if (!value.isArray() || value.isEmpty() || value.size() > maxItems) {
            throw new IllegalArgumentException("技法卡 " + field + " 必须是 1 到 " + maxItems + " 项的数组");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString() || item.asString().isBlank() || item.asString().length() > maxLength) {
                throw new IllegalArgumentException("技法卡 " + field + " 含非法项");
            }
            result.add(item.asString().strip());
        }
        return List.copyOf(result);
    }
}
