package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 稿件 JSON 契约闸：只判形状、必填、时长合计与「标题与屏句重复」。
 * 版式相关交给 hyperframes check，事实真伪不在这里判——三道自动闸判的都是形状。
 */
public final class NewsScriptContract {

    private static final Set<String> FIELDS = Set.of("source_url", "fetched_at", "body_sha256", "title", "scenes",
        "closing", "source_name");
    private static final Set<String> SCENE_FIELDS = Set.of("lead", "body", "duration_s");
    private static final int TITLE_CODE_POINTS = 15;
    private static final int MIN_SCENES = 3;
    private static final int MAX_SCENES = 6;
    private static final int MIN_TOTAL_SECONDS = 30;
    private static final int MAX_TOTAL_SECONDS = 90;

    private NewsScriptContract() { }

    /** 返回全部违例条目，调用方据此退回写稿；本类不改稿也不重试。 */
    public static List<String> violations(JsonNode script) {
        List<String> items = new ArrayList<>();
        if (script == null || !script.isObject()) {
            return List.of("稿件不是 JSON 对象");
        }
        for (String field : List.of("source_url", "fetched_at", "body_sha256", "title", "scenes", "closing",
            "source_name")) {
            if (!script.has(field)) items.add("缺字段 " + field);
        }
        for (String field : script.propertyNames()) {
            if (!FIELDS.contains(field)) items.add("未知字段 " + field);
        }
        text(items, script, "title", TITLE_CODE_POINTS);
        text(items, script, "closing", 200);
        text(items, script, "source_name", 80);
        if (script.has("body_sha256") && !script.path("body_sha256").asString().matches("[0-9a-f]{64}")) {
            items.add("body_sha256 不是完整 SHA-256");
        }
        if (script.has("source_url") && !script.path("source_url").asString().matches("https?://[^\\s]{1,2048}")) {
            items.add("source_url 不是 http(s) 链接");
        }
        scenes(items, script);
        return List.copyOf(items);
    }

    private static void scenes(List<String> items, JsonNode script) {
        JsonNode scenes = script.path("scenes");
        if (scenes.isMissingNode() || !scenes.isArray()) {
            if (script.has("scenes")) items.add("scenes 不是数组");
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
            text(items, scene, "lead", 40);
            text(items, scene, "body", 600);
            JsonNode duration = scene.path("duration_s");
            if (!duration.isNumber() || duration.asDouble() <= 0) items.add("第 " + (index + 1) + " 段缺少正数 duration_s");
            else total += duration.asDouble();
            if (!title.isBlank() && (title.equals(scene.path("lead").asString("")) || title.equals(scene.path("body").asString("")))) {
                items.add("第 " + (index + 1) + " 段与标题重复");
            }
        }
        if (total > 0 && (total < MIN_TOTAL_SECONDS || total > MAX_TOTAL_SECONDS)) {
            items.add("总时长 " + (int) total + " 秒不在 " + MIN_TOTAL_SECONDS + "–" + MAX_TOTAL_SECONDS + " 秒");
        }
    }

    private static void text(List<String> items, JsonNode node, String field, int maxCodePoints) {
        if (!node.has(field)) return;
        String value = node.path(field).asString("");
        if (value.isBlank()) items.add(field + " 为空");
        else if (value.codePointCount(0, value.length()) > maxCodePoints) items.add(field + " 超过 " + maxCodePoints + " 字");
    }
}
