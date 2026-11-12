package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板声明 supportedSceneTypes 是承诺，不是愿望。
 * 模型选了模板渲染不了的类型，画面和稿子就对不上——在渲染前拦住。
 */
@DisplayName("场景兼容闸：类型必须落在模板声明的能力里")
class SceneTypeGateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Path template(Path dir, String typesJson) throws Exception {
        Files.writeString(dir.resolve("template.json"), """
            {"name":"测试","supportedSceneTypes":%s}
            """.formatted(typesJson));
        return dir;
    }

    /** Gate 是 private record；测试只关心「过不过」，不关心返回结构。 */
    private static void check(JsonNode script, Path templateDir) {
        NewsProductionPipeline.sceneTypeGate(script, templateDir);
    }

    private static JsonNode script(String sceneTypes) {
        return JSON.readTree("""
            {"scenes":[{"scene_type":"%s","lead":"甲","body":"乙","duration_s":10}]}
            """.formatted(sceneTypes));
    }

    @Test
    @DisplayName("模板声明过的类型放行")
    void supportedTypePasses(@TempDir Path dir) throws Exception {
        Path tpl = template(dir, "[\"headline\",\"text\",\"quote\"]");
        assertDoesNotThrow(() -> check(script("quote"), tpl));
    }

    @Test
    @DisplayName("模板没声明的类型被拦，错误里写明声明了哪些")
    void unsupportedTypeBlocked(@TempDir Path dir) throws Exception {
        Path tpl = template(dir, "[\"headline\",\"text\"]");
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> check(script("rank"), tpl));
        assertEquals("scene_type_unsupported", e.getCode());
        assertTrue(e.getMessage().contains("rank"), e.getMessage());
        assertTrue(e.getMessage().contains("headline"), "错误里要列出模板实际支持什么：" + e.getMessage());
    }

    @Test
    @DisplayName("没有 scene_type 的 v1 稿不参与判定")
    void v1ScriptSkipsCheck(@TempDir Path dir) throws Exception {
        Path tpl = template(dir, "[\"headline\"]");
        JsonNode v1 = JSON.readTree("""
            {"scenes":[{"lead":"甲","body":"乙","duration_s":10}]}
            """);
        assertDoesNotThrow(() -> check(v1, tpl));
    }

    @Test
    @DisplayName("模板没写能力声明时不做类型约束（老模板兼容）")
    void undeclaredTemplateAllowsAny(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("template.json"), "{\"name\":\"老模板\"}");
        assertDoesNotThrow(() -> check(script("rank"), dir));
    }

    @Test
    @DisplayName("模板目录没有 template.json 时同样不阻断")
    void missingMetaDoesNotBlock(@TempDir Path dir) {
        assertDoesNotThrow(() -> check(script("rank"), dir));
    }
}
