package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("新闻流水线：确定性映射、时间轴补丁与闸报告")
class NewsProductionPipelineTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCRIPT = """
        {"source_url":"https://example.com/a","fetched_at":"2026-11-03T02:00:00Z",
         "body_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "title":"新一代旗舰手机发布",
         "scenes":[{"lead":"电池容量提升两成","body":"正文一","duration_s":10},
                   {"lead":"影像算法重写","body":"正文二","duration_s":10},
                   {"lead":"起售价不变","body":"正文三","duration_s":10}],
         "closing":"我们会持续跟进。","source_name":"公开报道整理"}
        """;
    private static final String SLOTS = """
        <div id="root" data-duration="45">
        <audio id="narration" data-start="0" data-duration="45">
        <header id="title-card" data-start="0" data-duration="45">
        <section id="chart-slot" data-start="0" data-duration="45">
        <section id="scene-1" data-start="0" data-duration="10">
        <section id="scene-2" data-start="10" data-duration="10">
        <section id="scene-3" data-start="20" data-duration="10">
        <section id="scene-4" data-start="30" data-duration="5">
        <section id="scene-5" data-start="35" data-duration="5">
        <section id="scene-6" data-start="40" data-duration="5">
        <aside id="closing-slot" data-start="20" data-duration="10">
        """;

    @Test
    @DisplayName("变量映射只搬文本，时长不进变量")
    void mapsOnlyTextVariables() {
        String vars = NewsProductionPipeline.templateVariables(JSON.readTree(SCRIPT));
        JsonNode node = JSON.readTree(vars);
        assertEquals("新一代旗舰手机发布", node.path("title").asString(""));
        assertEquals("公开报道整理", node.path("source_name").asString(""));
        assertEquals("我们会持续跟进。", node.path("closing").asString(""));
        assertEquals(3, node.path("scene_count").asInt());
        assertEquals("正文二", node.path("scene2_body").asString(""));
        assertTrue(node.path("scene4_lead").isMissingNode(), "未用槽位不写变量");
        assertFalse(vars.contains("duration"), "时长不走变量");
        assertEquals(vars, NewsProductionPipeline.templateVariables(JSON.readTree(SCRIPT)), "映射必须可复现");
    }

    @Test
    @DisplayName("时间轴按实测音频缩放，末段吃掉舍入余量")
    void scalesTimelineOntoDisk() {
        String patched = NewsProductionPipeline.timeCompositionString(SLOTS, JSON.readTree(SCRIPT), 41.5);
        assertEquals(41.5, attribute(patched, "root", "duration"), 0.001);
        assertEquals(41.5, attribute(patched, "narration", "duration"), 0.001);
        // 常驻层必须跟成片走，否则超过模板默认 45 秒后整层消失。
        assertEquals(41.5, attribute(patched, "title-card", "duration"), 0.001);
        assertEquals(41.5, attribute(patched, "chart-slot", "duration"), 0.001);
        assertEquals(0.0, attribute(patched, "scene-1", "start"), 0.001);
        assertEquals(13.83, attribute(patched, "scene-1", "duration"), 0.001);
        assertEquals(13.83, attribute(patched, "scene-2", "start"), 0.001);
        assertEquals(27.66, attribute(patched, "scene-3", "start"), 0.001);
        assertEquals(9.84, attribute(patched, "scene-3", "duration"), 0.001);
        double sum = attribute(patched, "scene-1", "duration") + attribute(patched, "scene-2", "duration")
            + attribute(patched, "scene-3", "duration") + attribute(patched, "closing-slot", "duration");
        assertEquals(41.5, sum, 0.001);
        // 结尾口播排在最后一屏之后，独立尾窗，不与最后一屏同屏。
        assertEquals(37.5, attribute(patched, "closing-slot", "start"), 0.001);
        assertEquals(4.0, attribute(patched, "closing-slot", "duration"), 0.001);
        assertEquals(attribute(patched, "scene-3", "start") + attribute(patched, "scene-3", "duration"),
            attribute(patched, "closing-slot", "start"), 0.001);
    }

    @Test
    @DisplayName("模板缺少槽位时直接失败，不静默少一段")
    void rejectsMissingSlot() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            NewsProductionPipeline.timeCompositionString("<div id=\"root\" data-duration=\"45\">",
                JSON.readTree(SCRIPT), 40));
        assertEquals("news_template_broken", error.getCode());
    }

    @Test
    @DisplayName("字幕只到 cue 级，从 VTT 解析出文本与毫秒区间")
    void parsesCueLevelSubtitles() {
        String vtt = "WEBVTT\n\n00:00:00,100 --> 00:00:05,762\n今天介绍一款新发布的智能手机。\n";
        JsonNode cues = JSON.readTree(NewsProductionPipeline.subtitleJson(vtt));
        assertEquals(1, cues.size());
        assertEquals(100, cues.get(0).path("startMs").asLong());
        assertEquals(5762, cues.get(0).path("endMs").asLong());
        assertEquals("今天介绍一款新发布的智能手机。", cues.get(0).path("text").asString(""));
    }

    @Test
    @DisplayName("未用槽位被摘到时间轴末尾，不留重叠文本")
    void retiresUnusedSlots() {
        String patched = NewsProductionPipeline.timeCompositionString(SLOTS, JSON.readTree(SCRIPT), 41.5);
        for (int slot = 4; slot <= 6; slot++) {
            assertEquals(41.5, attribute(patched, "scene-" + slot, "start"), 0.001);
            assertEquals(0.01, attribute(patched, "scene-" + slot, "duration"), 0.001);
        }
    }

    private static double attribute(String html, String id, String name) {
        Matcher tag = Pattern.compile("<[a-z]+[^<>]*\\bid=\"" + id + "\"[^<>]*>").matcher(html);
        assertTrue(tag.find(), "找不到槽位 " + id);
        Matcher attribute = Pattern.compile("data-" + name + "=\"([0-9.]+)\"").matcher(tag.group());
        assertTrue(attribute.find(), id + " 缺少 data-" + name);
        return Double.parseDouble(attribute.group(1));
    }
}
