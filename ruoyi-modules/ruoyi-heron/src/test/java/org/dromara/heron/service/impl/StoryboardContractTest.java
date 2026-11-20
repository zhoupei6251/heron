package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分镜契约：叙事节点、画面组件与上屏文字。 */
@DisplayName("分镜：叙事节点、组件与上屏文字")
class StoryboardContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 完整分镜：每段都有 beat 与 scene_type。 */
    private static JsonNode storyboard() {
        return JSON.readTree("""
            {"scenes": [
              {"lead": "金价站上关键位", "body": "最新收于 {{facts.last.value}} 美元。",
               "scene_type": "headline", "beat": "HOOK"},
              {"lead": "涨幅集中在下半年", "body": "下半年累计抬升 {{facts.change}} 美元。",
               "scene_type": "chart", "beat": "EVIDENCE"},
              {"lead": "拐点看下一期斜率", "body": "本期数据暂不支持反转。",
               "scene_type": "number", "beat": "CONCLUSION"}]}
            """);
    }

    @Test
    @DisplayName("完整分镜：节点成序列、组件有层次就放行")
    void completeStoryboardPasses() {
        assertTrue(StoryboardContract.violations(storyboard(), null).isEmpty());
    }

    @Test
    @DisplayName("旧稿没有 beat，一律放行")
    void legacyScriptWithoutBeatPasses() {
        JsonNode legacy = JSON.readTree("""
            {"scenes": [
              {"lead": "第一段", "body": "正文", "scene_type": "text"},
              {"lead": "第二段", "body": "正文", "scene_type": "text"},
              {"lead": "第三段", "body": "正文", "scene_type": "text"}]}
            """);
        assertTrue(StoryboardContract.violations(legacy, null).isEmpty());
    }

    @Test
    @DisplayName("只写几段 beat：分镜不完整，退回")
    void partialStoryboardRejected() {
        JsonNode partial = JSON.readTree("""
            {"scenes": [
              {"lead": "第一段", "body": "正文", "scene_type": "headline", "beat": "HOOK"},
              {"lead": "第二段", "body": "正文", "scene_type": "chart"},
              {"lead": "第三段", "body": "正文", "scene_type": "number", "beat": "CONCLUSION"}]}
            """);
        List<String> violations = StoryboardContract.violations(partial, null);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("分镜不完整"), violations.toString());
    }

    @Test
    @DisplayName("首屏不是 HOOK、末屏不是结论：退回")
    void badSequenceRejected() {
        JsonNode bad = JSON.readTree("""
            {"scenes": [
              {"lead": "第一段", "body": "正文", "scene_type": "headline", "beat": "CONTEXT"},
              {"lead": "第二段", "body": "正文", "scene_type": "chart", "beat": "EVIDENCE"},
              {"lead": "第三段", "body": "正文", "scene_type": "number", "beat": "EVIDENCE"}]}
            """);
        List<String> violations = StoryboardContract.violations(bad, null);
        assertEquals(2, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("首屏"), violations.toString());
        assertTrue(violations.get(1).contains("末屏"), violations.toString());
    }

    @Test
    @DisplayName("整篇一个组件：画面没有层次，退回")
    void singleComponentRejected() {
        JsonNode flat = JSON.readTree("""
            {"scenes": [
              {"lead": "第一段", "body": "正文", "scene_type": "text", "beat": "HOOK"},
              {"lead": "第二段", "body": "正文", "scene_type": "text", "beat": "EVIDENCE"},
              {"lead": "第三段", "body": "正文", "scene_type": "text", "beat": "CONCLUSION"}]}
            """);
        List<String> violations = StoryboardContract.violations(flat, null);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("没有层次"), violations.toString());
    }

    @Test
    @DisplayName("未知 beat 与未知组件都退回")
    void unknownValuesRejected() {
        JsonNode bad = JSON.readTree("""
            {"scenes": [
              {"lead": "第一段", "body": "正文", "scene_type": "headline", "beat": "HOOK"},
              {"lead": "第二段", "body": "正文", "scene_type": "hologram", "beat": "EXPLAIN"},
              {"lead": "第三段", "body": "正文", "scene_type": "number", "beat": "CONCLUSION"}]}
            """);
        List<String> violations = StoryboardContract.violations(bad, null);
        assertEquals(2, violations.size(), violations.toString());
    }

    @Test
    @DisplayName("上屏文字超长：按解析后的字数算，不按占位符原文")
    void screenTextLengthUsesResolvedText() {
        JsonNode script = JSON.readTree("""
            {"scenes": [
              {"lead": "第一段", "body": "正文", "scene_type": "headline", "beat": "HOOK",
               "on_screen_text": "{{facts.last.value}} 美元站上关键位"},
              {"lead": "第二段", "body": "正文", "scene_type": "chart", "beat": "EVIDENCE"},
              {"lead": "第三段", "body": "正文", "scene_type": "number", "beat": "CONCLUSION"}]}
            """);
        // 原文 29 字：按原文判会超限
        assertTrue(StoryboardContract.violations(script, null).stream()
            .anyMatch(v -> v.contains("on_screen_text")), "原文超长应被拦下");
        // 解析后 12 字：按解析结果判就放行
        JsonNode resolved = JSON.readTree("""
            {"scenes": [
              {"on_screen_text": "4091.55 美元站上关键位"},
              {}, {}]}
            """);
        assertTrue(StoryboardContract.violations(script, resolved).stream()
            .noneMatch(v -> v.contains("on_screen_text")), "解析后不超长就不该被判超长");
    }
}
