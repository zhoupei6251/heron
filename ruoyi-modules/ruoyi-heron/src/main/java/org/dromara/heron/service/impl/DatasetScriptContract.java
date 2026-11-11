package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据看板脚本契约：形状归这里判，数字与占位符归 DatasetContract 判。
 * 与文章稿分开，因为出处是数据集而不是正文。
 */
public final class DatasetScriptContract {

    private static final Set<String> FIELDS = Set.of("dataset_sha256", "source_key", "title", "scenes", "closing",
        "source_name", "chart");
    private static final Set<String> SCENE_FIELDS = Set.of("lead", "body", "duration_s");
    private static final int TITLE_CODE_POINTS = 15;
    private static final int MIN_SCENES = 3;
    private static final int MAX_SCENES = 6;
    private static final int MIN_TOTAL_SECONDS = 30;
    private static final int MAX_TOTAL_SECONDS = 75;

    private DatasetScriptContract() { }

    /** 数据形态 → 图表模块；模板侧按这个值选绘图区。 */
    public static String chartFor(String shape) {
        return switch (shape) {
            case "wide" -> "line";
            case "long" -> "race";
            case "rows" -> "bars";
            case "geo" -> "map";
            default -> "";
        };
    }

    public static List<String> violations(JsonNode script, JsonNode snapshot) {
        if (script == null || !script.isObject()) return List.of("脚本不是 JSON 对象");
        List<String> items = new ArrayList<>();
        for (String field : List.of("dataset_sha256", "source_key", "title", "scenes", "closing", "source_name",
            "chart")) {
            if (!script.has(field)) items.add("缺字段 " + field);
        }
        for (String field : script.propertyNames()) {
            if (!FIELDS.contains(field)) items.add("未知字段 " + field);
        }
        String expectedSha = snapshot.path("data_sha256").asString("");
        if (!expectedSha.equals(script.path("dataset_sha256").asString(""))) {
            items.add("dataset_sha256 与快照不一致");
        }
        String expectedKey = snapshot.path("source_key").asString("");
        if (!expectedKey.equals(script.path("source_key").asString(""))) {
            items.add("source_key 与快照不一致");
        }
        String expectedChart = chartFor(snapshot.path("shape").asString(""));
        if (!expectedChart.equals(script.path("chart").asString(""))) {
            items.add("chart 与数据形态不一致，应为 " + expectedChart);
        }
        title(items, script, snapshot);
        text(items, script, "closing", 200);
        text(items, script, "source_name", 80);
        scenes(items, script);
        narration(items, script, snapshot);
        return List.copyOf(new java.util.LinkedHashSet<>(items));
    }

    private static void scenes(List<String> items, JsonNode script) {
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray()) {
            items.add("scenes 不是数组");
            return;
        }
        if (scenes.size() < MIN_SCENES || scenes.size() > MAX_SCENES) {
            items.add("段落数 " + scenes.size() + " 不在 " + MIN_SCENES + "–" + MAX_SCENES);
        }
        String title = script.path("title").asString("");
        double total = 0;
        for (int index = 0; index < scenes.size(); index++) {
            JsonNode scene = scenes.get(index);
            if (!scene.isObject()) {
                items.add("第 " + (index + 1) + " 段不是对象");
                continue;
            }
            for (String field : scene.propertyNames()) {
                if (!SCENE_FIELDS.contains(field)) items.add("第 " + (index + 1) + " 段有未知字段 " + field);
            }
            text(items, scene, "lead", 20);
            text(items, scene, "body", 70);
            JsonNode duration = scene.path("duration_s");
            if (!duration.isNumber() || duration.asDouble() <= 0) {
                items.add("第 " + (index + 1) + " 段缺少正数 duration_s");
            } else {
                total += duration.asDouble();
            }
            if (!title.isBlank()
                && (title.equals(scene.path("lead").asString("")) || title.equals(scene.path("body").asString("")))) {
                items.add("第 " + (index + 1) + " 段与标题重复");
            }
        }
        if (total > 0 && (total < MIN_TOTAL_SECONDS || total > MAX_TOTAL_SECONDS)) {
            items.add("总时长 " + (int) total + " 秒不在 " + MIN_TOTAL_SECONDS + "–" + MAX_TOTAL_SECONDS + " 秒");
        }
    }

    private static void narration(List<String> items, JsonNode script, JsonNode snapshot) {
        StringBuilder text = new StringBuilder();
        for (JsonNode scene : script.path("scenes")) {
            text.append(scene.path("lead").asString("")).append('。')
                .append(scene.path("body").asString("")).append('。');
        }
        text.append(script.path("closing").asString(""));
        for (String violation : DatasetContract.narrationViolations(snapshot, text.toString())) {
            items.add("旁白：" + violation);
        }
    }

    /** 标题允许占位符，但上限按解析后的字数算，避免“占位符很长、上屏很短”绕过限制。 */
    private static void title(List<String> items, JsonNode script, JsonNode snapshot) {
        String value = script.path("title").asString("");
        if (value.isBlank()) {
            items.add("title 为空");
            return;
        }
        int precision = snapshot.path("metric").path("precision").asInt(2);
        String resolved;
        try {
            resolved = DatasetPlaceholders.fill(value, snapshot, precision);
        } catch (RuntimeException e) {
            items.add("title 占位符无法解析");
            return;
        }
        int length = resolved.codePointCount(0, resolved.length());
        if (length > TITLE_CODE_POINTS) {
            items.add("title " + length + " 字，超过上限 " + TITLE_CODE_POINTS + " 字");
        }
    }

    private static void text(List<String> items, JsonNode node, String field, int maxCodePoints) {
        if (!node.has(field)) return;
        String value = node.path(field).asString("");
        int length = value.codePointCount(0, value.length());
        if (value.isBlank()) items.add(field + " 为空");
        else if (length > maxCodePoints) items.add(field + " " + length + " 字，超过上限 " + maxCodePoints + " 字");
    }
}
