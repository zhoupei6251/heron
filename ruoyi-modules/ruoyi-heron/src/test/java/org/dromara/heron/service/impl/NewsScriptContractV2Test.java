package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v1 与 v2 并存：没有 scene_type 的段落按老规则判，有的按新规则判。
 * 升级契约不能把旧稿判死，否则历史作品连重渲都做不了。
 */
@DisplayName("稿件契约：v1 兼容与 v2 场景")
class NewsScriptContractV2Test {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HEAD = """
        "source_url":"https://example.com/a","fetched_at":"2026-11-12T00:00:00",
        "body_sha256":"%s","title":"测试标题","source_name":"示例站","closing":"结尾一句"
        """.formatted("a".repeat(64));

    private static List<String> check(String body) {
        JsonNode script = JSON.readTree("{" + HEAD + "," + body + "}");
        return NewsScriptContract.violations(script);
    }

    private static String scene(String lead, String extra) {
        return "{\"lead\":\"" + lead + "\",\"body\":\"正文正文正文正文\",\"duration_s\":10" + extra + "}";
    }

    @Test
    @DisplayName("没有 scene_type 的老稿照旧合法")
    void v1ScriptStillValid() {
        assertTrue(check("\"scenes\":[" + scene("一", "") + "," + scene("二", "") + ","
            + scene("三", "") + "]").isEmpty());
    }

    @Test
    @DisplayName("文字场景带 scene_type 也合法")
    void v2TextSceneValid() {
        assertTrue(check("\"scenes\":[" + scene("一", ",\"scene_type\":\"text\"") + ","
            + scene("二", ",\"scene_type\":\"text\"") + ","
            + scene("三", ",\"scene_type\":\"headline\"") + "]").isEmpty());
    }

    @Test
    @DisplayName("图片场景缺 visual 被判违例")
    void imageSceneRequiresVisual() {
        List<String> items = check("\"scenes\":[" + scene("一", ",\"scene_type\":\"image\"") + ","
            + scene("二", "") + "," + scene("三", "") + "]");
        assertTrue(items.stream().anyMatch(s -> s.contains("必须给 visual")), items.toString());
    }

    @Test
    @DisplayName("图片场景的 assets 不能为空")
    void imageSceneRequiresAssets() {
        List<String> items = check("\"scenes\":[" + scene("一",
            ",\"scene_type\":\"image\",\"visual\":{\"assets\":[]}") + ","
            + scene("二", "") + "," + scene("三", "") + "]");
        assertTrue(items.stream().anyMatch(s -> s.contains("assets 为空")), items.toString());
    }

    @Test
    @DisplayName("带合法资产的图片场景通过")
    void imageSceneWithAssetValid() {
        assertTrue(check("\"scenes\":[" + scene("一",
            ",\"scene_type\":\"image\",\"visual\":{\"layout\":\"image_with_context\",\"assets\":["
                + "{\"asset_id\":\"img_1\",\"source_url\":\"https://example.com/p.jpg\"}]}")
            + "," + scene("二", "") + "," + scene("三", "") + "]").isEmpty());
    }

    @Test
    @DisplayName("未知场景类型被拒")
    void unknownSceneTypeRejected() {
        List<String> items = check("\"scenes\":[" + scene("一", ",\"scene_type\":\"hologram\"") + ","
            + scene("二", "") + "," + scene("三", "") + "]");
        assertTrue(items.stream().anyMatch(s -> s.contains("scene_type 未知")), items.toString());
    }

    @Test
    @DisplayName("资产不能带任意文件路径以外的未知字段")
    void assetUnknownFieldRejected() {
        List<String> items = check("\"scenes\":[" + scene("一",
            ",\"scene_type\":\"image\",\"visual\":{\"assets\":[{\"asset_id\":\"img_1\",\"localPath\":\"/etc/passwd\"}]}")
            + "," + scene("二", "") + "," + scene("三", "") + "]");
        assertTrue(items.stream().anyMatch(s -> s.contains("未知字段 localPath")), items.toString());
    }

    @Test
    @DisplayName("schema_version 只认 1 和 2")
    void schemaVersionValidated() {
        List<String> items = check("\"schema_version\":\"3\",\"scenes\":[" + scene("一", "") + ","
            + scene("二", "") + "," + scene("三", "") + "]");
        assertTrue(items.stream().anyMatch(s -> s.contains("schema_version")), items.toString());
    }
}
