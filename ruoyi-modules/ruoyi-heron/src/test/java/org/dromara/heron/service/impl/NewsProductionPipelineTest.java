package org.dromara.heron.service.impl;

import cn.hutool.core.util.EscapeUtil;
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
    private static final String VTT = """
        WEBVTT

        00:00:00.100 --> 00:00:02.000
        电池容量提升两成。

        00:00:02.000 --> 00:00:06.000
        正文一。

        00:00:06.000 --> 00:00:08.500
        影像算法重写。

        00:00:08.500 --> 00:00:20.000
        正文二。

        00:00:20.000 --> 00:00:22.000
        起售价不变。

        00:00:22.000 --> 00:00:28.000
        正文三。

        00:00:28.000 --> 00:00:41.400
        我们会持续跟进。
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
    @DisplayName("变量只输出模板声明过的键：多给一个就会被 --strict-variables 中止渲染")
    void variableKeysStayWithinTemplateDeclaration() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..", "..", "data", "templates")
            .toAbsolutePath().normalize();
        assertTrue(java.nio.file.Files.isDirectory(root), "模板目录不存在：" + root);
        JsonNode script = JSON.readTree(SCRIPT);
        JsonNode dataset = JSON.readTree("""
            {"shape":"rows","base":"usd","period":"2026-11","data_sha256":"%s","source_key":"sina-futures-gold",
             "metric":{"key":"gold","label":"金价","unit":"美元","precision":2}}
            """.formatted("b".repeat(64)));
        java.util.List<java.nio.file.Path> dirs;
        try (var stream = java.nio.file.Files.list(root)) {
            // data/templates 下还住着生成器的输入（_gen / _shared / layouts），它们不是模板；
            // 「有 index.html」才是模板的判定，跟 NewsProductionDomain.availableTemplates 一致
            dirs = stream.filter(java.nio.file.Files::isDirectory)
                .filter(dir -> java.nio.file.Files.isRegularFile(dir.resolve("index.html")))
                .sorted().toList();
        }
        assertFalse(dirs.isEmpty(), "模板目录里没有模板");
        for (java.nio.file.Path dir : dirs) {
            java.util.Set<String> declared = NewsProductionPipeline.declaredVariables(dir);
            assertFalse(declared.isEmpty(), dir.getFileName() + " 没声明任何变量");
            for (JsonNode vars : java.util.List.of(
                JSON.readTree(NewsProductionPipeline.templateVariables(script, java.util.Map.of(), declared)),
                JSON.readTree(NewsProductionPipeline.dataTemplateVariables(script, dataset, declared)))) {
                for (String key : vars.propertyNames()) {
                    assertTrue(declared.contains(key), dir.getFileName() + " 会收到未声明变量：" + key);
                }
            }
        }
    }

    @Test
    @DisplayName("模板未声明能力时全部输出，不清空变量")
    void undeclaredTemplateKeepsAllVariables() {
        JsonNode vars = JSON.readTree(NewsProductionPipeline.templateVariables(
            JSON.readTree(SCRIPT), java.util.Map.of(), java.util.Set.of()));
        assertEquals("新一代旗舰手机发布", vars.path("title").asString(""));
        assertEquals("", vars.path("scene1_image").asString("x"), "无图场景给空串");
    }

    @Test
    @DisplayName("检查工作副本绑定真实标题、段数与数据，保留未覆盖的风格值")
    void bindsRealContentBeforeChecking() {
        String html = """
            <html data-composition-variables='[
              {"id":"title","type":"string","default":"占位标题"},
              {"id":"scene_count","type":"number","default":6},
              {"id":"dataset_json","type":"string","default":"{}"},
              {"id":"accent","type":"color","default":"#18b7c9"}]'>
            <body><h1 data-var-text="title">占位标题</h1></body></html>
            """;
        String title = "真实 O'Reilly <数据> & \"中文\"";
        String values = JSON.writeValueAsString(java.util.Map.of(
            "title", title, "scene_count", 3, "dataset_json", "{\"label\":\"中国\"}"));

        String bound = NewsProductionPipeline.bindVariablesString(html, values);
        Matcher declaration = Pattern.compile("data-composition-variables='([^']*)'").matcher(bound);
        assertTrue(declaration.find(), "引号必须转义，不能截断HTML属性");
        JsonNode defaults = JSON.readTree(EscapeUtil.unescapeHtml4(declaration.group(1)));
        assertEquals(title, defaults.get(0).path("default").asString());
        assertEquals(3, defaults.get(1).path("default").asInt());
        assertEquals("中国", JSON.readTree(defaults.get(2).path("default").asString()).path("label").asString());
        assertEquals("#18b7c9", defaults.get(3).path("default").asString());
        assertFalse(bound.contains("<数据>"), "数据文本不能变成HTML节点");
        assertTrue(bound.contains("<h1 data-var-text=\"title\">"), "保留模板的声明式绑定");
    }

    @Test
    @DisplayName("没有变量声明或收到未声明变量时拒绝检查，不能默默验占位稿")
    void rejectsUnboundVariables() {
        assertEquals("news_variables_invalid", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.bindVariablesString("<html></html>", "{\"title\":\"真实\"}")).getCode());
        assertEquals("news_variables_invalid", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.bindVariablesString(
                "<html data-composition-variables='[]'></html>", "{\"title\":\"真实\"}")).getCode());
    }

    @Test
    @DisplayName("时间轴使用实际句界，声明等长的三段也可以有不同口播长度")
    void alignsTimelineOntoDisk() {
        String patched = NewsProductionPipeline.timeCompositionString(SLOTS, JSON.readTree(SCRIPT), 41.5, VTT);
        assertEquals(41.5, attribute(patched, "root", "duration"), 0.001);
        assertEquals(41.5, attribute(patched, "narration", "duration"), 0.001);
        // 常驻层必须跟成片走，否则超过模板默认 45 秒后整层消失。
        assertEquals(41.5, attribute(patched, "title-card", "duration"), 0.001);
        assertEquals(41.5, attribute(patched, "chart-slot", "duration"), 0.001);
        assertEquals(0.0, attribute(patched, "scene-1", "start"), 0.001);
        assertEquals(6.0, attribute(patched, "scene-1", "duration"), 0.001);
        assertEquals(6.0, attribute(patched, "scene-2", "start"), 0.001);
        assertEquals(20.0, attribute(patched, "scene-3", "start"), 0.001);
        assertEquals(8.0, attribute(patched, "scene-3", "duration"), 0.001);
        double sum = attribute(patched, "scene-1", "duration") + attribute(patched, "scene-2", "duration")
            + attribute(patched, "scene-3", "duration") + attribute(patched, "closing-slot", "duration");
        assertEquals(41.5, sum, 0.001);
        // 结尾口播排在最后一屏之后，独立尾窗，不与最后一屏同屏。
        assertEquals(28.0, attribute(patched, "closing-slot", "start"), 0.001);
        assertEquals(13.5, attribute(patched, "closing-slot", "duration"), 0.001);
        assertEquals(attribute(patched, "scene-3", "start") + attribute(patched, "scene-3", "duration"),
            attribute(patched, "closing-slot", "start"), 0.001);
    }

    @Test
    @DisplayName("配乐释放与淡出使用实测结尾，不使用三段各十秒的声明")
    void mixesBgmOnMeasuredClosing() {
        var timeline = NewsNarrationTiming.align(JSON.readTree(SCRIPT),
            JSON.readTree(NewsProductionPipeline.subtitleJson(VTT)), 41500);
        String filter = NewsProductionPipeline.bgmFilter(timeline.get(timeline.size() - 1));
        assertTrue(filter.contains("(t-28.000)/0.800"), "第28秒才进入实际结尾");
        assertTrue(filter.contains("afade=t=out:st=40.300:d=1.200"), "完整音频末尾才淡出");
        assertTrue(filter.contains("atrim=duration=41.500"));
        assertTrue(filter.contains("eval=frame"), "音量应连续变化");
        assertFalse(filter.contains("enable="), "不能在结尾瞬间跳档");
    }

    @Test
    @DisplayName("短结尾内完成释放和淡出，非法时间不退回猜测时长")
    void handlesShortClosingWithoutInventingTiming() {
        String filter = NewsProductionPipeline.bgmFilter(new NewsNarrationTiming.Span("closing-slot", 41000, 41500, false));
        assertTrue(filter.contains("(t-41.000)/0.250"));
        assertTrue(filter.contains("afade=t=out:st=41.250:d=0.250"));
        assertThrows(HeronBusinessException.class, () -> NewsProductionPipeline.bgmFilter(
            new NewsNarrationTiming.Span("scene-1", 0, 41000, false)));
        assertThrows(HeronBusinessException.class, () -> NewsProductionPipeline.bgmFilter(
            new NewsNarrationTiming.Span("closing-slot", 41000, 40000, false)));
    }

    @Test
    @DisplayName("模板缺少槽位时直接失败，不静默少一段")
    void rejectsMissingSlot() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            NewsProductionPipeline.timeCompositionString("<div id=\"root\" data-duration=\"45\">",
                JSON.readTree(SCRIPT), 41.5, VTT));
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
    @DisplayName("多行字幕与不同小数位时间戳都保留，不能只对齐第一行")
    void parsesMultilineCueAndFractionalTimestamps() {
        JsonNode cues = JSON.readTree(NewsProductionPipeline.subtitleJson(
            "WEBVTT\n\n00:00:00.1 --> 00:00:02.50\n第一行\n第二行\n\n"));
        assertEquals(1, cues.size());
        assertEquals(100, cues.get(0).path("startMs").asLong());
        assertEquals(2500, cues.get(0).path("endMs").asLong());
        assertEquals("第一行\n第二行", cues.get(0).path("text").asString());
    }

    @Test
    @DisplayName("未用槽位被摘到时间轴末尾，不留重叠文本")
    void retiresUnusedSlots() {
        String patched = NewsProductionPipeline.timeCompositionString(SLOTS, JSON.readTree(SCRIPT), 41.5, VTT);
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
