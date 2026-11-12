package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 稿件 JSON 契约闸：只判形状、必填、时长合计与「标题与屏句重复」。
 * 版式相关交给 hyperframes check，事实真伪不在这里判——三道自动闸判的都是形状。
 *
 * <p>两个版本并存：
 * <ul>
 *   <li>v1：段落只有 lead / body / duration_s，纯文字。</li>
 *   <li>v2：段落额外可带 scene_type 与 visual（画面资产），用于图片、图表等非文字场景。</li>
 * </ul>
 * 判定按段落逐个进行：没有 scene_type 的段落按 v1 校验，有的按 v2 校验。
 * 这样旧稿继续合法，新稿可以在同一份脚本里混用——升级契约不会让历史作品无法重渲。
 */
public final class NewsScriptContract {

    private static final Set<String> FIELDS = Set.of("source_url", "fetched_at", "body_sha256", "title", "scenes",
        "closing", "source_name", "schema_version");
    /** v1 段落字段。 */
    private static final Set<String> SCENE_FIELDS_V1 = Set.of("lead", "body", "duration_s");
    /** v2 在 v1 之上增加的字段；scene_type 是启用 v2 校验的开关。 */
    private static final Set<String> SCENE_V2_EXTRA = Set.of("scene_type", "visual");
    private static final Set<String> VISUAL_FIELDS = Set.of("layout", "motion", "assets");
    private static final Set<String> ASSET_FIELDS = Set.of("asset_id", "role", "source_url");
    /** 允许出现在稿件里的场景类型。新增类型要同时出现在模板 template.json 的 supportedSceneTypes。 */
    private static final Set<String> SCENE_TYPES = Set.of(
        "headline", "text", "image", "chart", "quote", "list", "compare",
        "number", "qa", "timeline", "rank");
    private static final int TITLE_CODE_POINTS = 15;
    private static final int MIN_SCENES = 3;
    private static final int MAX_SCENES = 6;
    private static final int MIN_TOTAL_SECONDS = 30;
    private static final int MAX_TOTAL_SECONDS = 75;

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
        String declared = script.path("schema_version").asString("");
        if (!declared.isBlank() && !"1".equals(declared) && !"2".equals(declared)) {
            items.add("schema_version 只支持 1 或 2，收到 " + declared);
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
            boolean v2 = scene.has("scene_type");
            Set<String> allowed = v2 ? union(SCENE_FIELDS_V1, SCENE_V2_EXTRA) : SCENE_FIELDS_V1;
            for (String field : scene.propertyNames()) {
                if (!allowed.contains(field)) items.add("第 " + (index + 1) + " 段有未知字段 " + field);
            }
            text(items, scene, "lead", 20);
            text(items, scene, "body", 70);
            JsonNode duration = scene.path("duration_s");
            if (!duration.isNumber() || duration.asDouble() <= 0) items.add("第 " + (index + 1) + " 段缺少正数 duration_s");
            else total += duration.asDouble();
            if (!title.isBlank() && (title.equals(scene.path("lead").asString("")) || title.equals(scene.path("body").asString("")))) {
                items.add("第 " + (index + 1) + " 段与标题重复");
            }
            if (v2) visual(items, index, scene);
        }
        if (total > 0 && (total < MIN_TOTAL_SECONDS || total > MAX_TOTAL_SECONDS)) {
            items.add("总时长 " + (int) total + " 秒不在 " + MIN_TOTAL_SECONDS + "–" + MAX_TOTAL_SECONDS + " 秒");
        }
    }

    /** v2 段落：场景类型必须已知，visual 可选但一旦出现就要形状正确。 */
    private static void visual(List<String> items, int index, JsonNode scene) {
        int number = index + 1;
        String type = scene.path("scene_type").asString("");
        if (type.isBlank()) {
            items.add("第 " + number + " 段 scene_type 为空");
        } else if (!SCENE_TYPES.contains(type)) {
            items.add("第 " + number + " 段 scene_type 未知：" + type);
        }
        JsonNode visual = scene.path("visual");
        if (visual.isMissingNode()) {
            // 文字类场景可以不带 visual；非文字场景必须带，否则模板没有可填的东西
            if ("image".equals(type) || "chart".equals(type)) {
                items.add("第 " + number + " 段是 " + type + " 场景，必须给 visual");
            }
            return;
        }
        if (!visual.isObject()) {
            items.add("第 " + number + " 段 visual 不是对象");
            return;
        }
        for (String field : visual.propertyNames()) {
            if (!VISUAL_FIELDS.contains(field)) items.add("第 " + number + " 段 visual 有未知字段 " + field);
        }
        JsonNode assets = visual.path("assets");
        if (assets.isMissingNode()) {
            if ("image".equals(type) || "chart".equals(type)) {
                items.add("第 " + number + " 段的 " + type + " 场景缺少 assets");
            }
            return;
        }
        if (!assets.isArray()) {
            items.add("第 " + number + " 段 visual.assets 不是数组");
            return;
        }
        if (("image".equals(type)) && assets.isEmpty()) {
            items.add("第 " + number + " 段是 image 场景但 assets 为空");
        }
        for (int i = 0; i < assets.size(); i++) {
            JsonNode asset = assets.get(i);
            if (!asset.isObject()) {
                items.add("第 " + number + " 段第 " + (i + 1) + " 个资产不是对象");
                continue;
            }
            for (String field : asset.propertyNames()) {
                if (!ASSET_FIELDS.contains(field)) items.add("第 " + number + " 段资产有未知字段 " + field);
            }
            String id = asset.path("asset_id").asString("");
            if (id.isBlank()) items.add("第 " + number + " 段第 " + (i + 1) + " 个资产缺 asset_id");
            // 模型不能直接指定文件路径：只认资产 ID，由 Asset Resolver 换成实际文件
            if (!asset.path("source_url").asString("").isBlank()
                && !asset.path("source_url").asString("").matches("https?://[^\\s]{1,2048}")) {
                items.add("第 " + number + " 段资产 source_url 不是 http(s) 链接");
            }
        }
    }

    private static Set<String> union(Set<String> base, Set<String> extra) {
        Set<String> merged = new java.util.HashSet<>(base);
        merged.addAll(extra);
        return merged;
    }

    private static void text(List<String> items, JsonNode node, String field, int maxCodePoints) {
        if (!node.has(field)) return;
        String value = node.path(field).asString("");
        int length = value.codePointCount(0, value.length());
        if (value.isBlank()) items.add(field + " 为空");
        else if (length > maxCodePoints) items.add(field + " " + length + " 字，超过上限 " + maxCodePoints + " 字");
    }
}
