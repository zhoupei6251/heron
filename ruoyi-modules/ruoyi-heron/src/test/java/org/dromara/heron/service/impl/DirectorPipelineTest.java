package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorPipelineTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path work;

    @Test
    void factIssuesAndFailedRechecksCannotReleaseTheAutomaticPath() {
        var report = JSON.readTree("""
            {"screenshot":{"verdict":"needs_review","findings":[{"tile":1,"kind":"readability"},{"tile":1,"kind":"fact"}]}}
            """);
        assertTrue(DirectorVisualRework.candidates(report, List.of(new DirectorVisualRework.Sample(30, 1000, "scene-1"))).isEmpty());
        assertFalse(DirectorVisualRework.accepted(report));
        assertFalse(DirectorVisualRework.accepted(JSON.createObjectNode()));
        var error = assertThrows(HeronBusinessException.class, () -> NewsWorkServiceImpl.checkVisualPass(report.toString()));
        assertEquals("visual_review_required", error.getCode());
        assertThrows(HeronBusinessException.class,
            () -> NewsWorkServiceImpl.checkVisualPass("{\"screenshot\":{\"verdict\":\"undetermined\"}}"));
        assertThrows(HeronBusinessException.class,
            () -> NewsWorkServiceImpl.checkVisualPass("{\"screenshot\":{\"verdict\":\"pass\"}}"));
        NewsWorkServiceImpl.checkVisualPass("{\"screenshot\":{\"verdict\":\"pass\",\"frames\":6,\"findings\":[]}}");
        assertThrows(HeronBusinessException.class,
            () -> NewsWorkServiceImpl.checkVisualPass("{\"screenshot\":{\"frames\":0}}"),
            "fail-closed：没有视觉结论的报告不能当默认通过");
    }

    @Test
    void landscapeVariantSelectsItsOwnProfileAndKeepsCanvasVocabularyClosed() {
        Path root = Path.of("../..").toAbsolutePath().normalize();
        var properties = new HeronProductionProperties();
        properties.setNewsProfile(root.resolve("data/profiles"));
        var registry = new ProfileRegistry(properties);
        // 命名即画幅：后缀进 select 封闭词汇，未知基础风格回落线内默认再重组
        assertEquals("data-board-landscape", ProfileRegistry.select(ProductionBizType.data_board, "data-board-landscape"));
        assertEquals("data-board-landscape", ProfileRegistry.select(ProductionBizType.data_board, "sprint-landscape"));
        assertEquals("sprint-landscape", ProfileRegistry.select(ProductionBizType.news_explainer, "sprint-landscape"));
        assertEquals("article-landscape", ProfileRegistry.select(ProductionBizType.news_explainer, "article-landscape"));
        assertEquals("data-board", ProfileRegistry.select(ProductionBizType.data_board, null));
        var profile = registry.load(ProductionBizType.data_board, "data-board-landscape",
            ProfileRegistry.DEFAULT_VOICE);
        assertEquals(1920, profile.width());
        assertEquals(1080, profile.height());
        assertEquals(720, profile.subtitleTop());
        assertTrue(profile.landscape());
        // 不存在的横屏目录（命名画幅绑定正确但资产缺失）必须拒绝而不是静默回退竖屏
        assertThrows(HeronBusinessException.class,
            () -> registry.load(ProductionBizType.data_board, "sprint-landscape", ProfileRegistry.DEFAULT_VOICE));
    }

    @Test
    void compilesSourceBoundScenesWithoutLoadingOldTemplates() throws Exception {
        Path root = Path.of("../..").toAbsolutePath().normalize();
        var properties = new HeronProductionProperties();
        properties.setNewsProfile(root.resolve("data/profiles"));
        var registry = new ProfileRegistry(properties);
        for (var line : ProductionBizType.videoLanes()) {
            var profile = registry.load(line, ProfileRegistry.select(line, null), ProfileRegistry.DEFAULT_VOICE);
            assertEquals(line.name(), profile.line());
            assertEquals(64, profile.fingerprint().length());
        }
        assertThrows(HeronBusinessException.class,
            () -> registry.load(ProductionBizType.data_board, "../article", ProfileRegistry.DEFAULT_VOICE));
        var profile = registry.load(ProductionBizType.data_board, "data-board", ProfileRegistry.DEFAULT_VOICE);
        var dataset = JSON.readTree("""
            {"shape":"wide","metric":{"key":"指数","precision":1,"unit":"点"},
             "rows":[["2025-01-01",80.2],["2025-03-01",83.4],["2025-06-01",103.3]],"fixture":"</script><img src=x>"}
            """);
        var script = JSON.readTree("""
            {"title":"半年后，指数为什么变了？","source_name":"测试来源","scenes":[
              {"lead":"用这组数重新看","on_screen_text":"走势反转 <不是标签>"}, {"lead":"从三月开始变快"}]}
            """);
        var timeline = List.of(new NewsNarrationTiming.Span("scene-1", 0, 3500, false),
            new NewsNarrationTiming.Span("scene-2", 3500, 7100, true),
            new NewsNarrationTiming.Span("closing-slot", 7100, 10300, false));
        var shots = DirectorPipeline.storyboard(script, dataset, Map.of(), timeline, profile);
        assertEquals(List.of("hook", "chart", "close"), shots.stream().map(DirectorPipeline.Shot::treatment).toList());
        assertTrue(shots.get(1).interpolated());
        assertThrows(HeronBusinessException.class, () -> DirectorPipeline.storyboard(script, dataset,
            Map.of("1", "../escape.jpg"), timeline, profile));
        Path composition = DirectorPipeline.prepare(work, profile);
        DirectorPipeline.compose(composition, profile, script, dataset, shots);
        String html = Files.readString(composition.resolve("index.html"));
        assertTrue(html.contains("data-start=\"3.500\" data-duration=\"3.600\""));
        assertTrue(html.contains("&lt;不是标签&gt;"));
        assertFalse(html.contains("</script><img src=x>"));
        assertEquals(dataset, JSON.readTree(Files.readString(composition.resolve("dataset.json"))));
        assertEquals("tts-cues", JSON.readTree(Files.readString(composition.resolve("storyboard.json"))).path("timingSource").asText());
        var samples = DirectorVisualRework.samples(shots, 6);
        assertEquals("scene-1", samples.getFirst().shotId());
        assertEquals("scene-2", samples.get(2).shotId());
        assertEquals("closing-slot", samples.getLast().shotId());
        var review = JSON.readTree("""
            {"screenshot":{"verdict":"needs_review","findings":[{"tile":3,"kind":"readability"},{"tile":4,"kind":"readability"}]}}
            """);
        var patch = DirectorVisualRework.candidates(review, samples).getFirst();
        assertEquals("scene-2", patch.shotId());
        assertEquals(1, DirectorVisualRework.candidates(review, samples).size());
        DirectorPipeline.compose(composition, profile, script, dataset, shots, patch, false);
        String corrected = Files.readString(composition.resolve("index.html"));
        assertEquals(html, corrected.replace("clip shot chart rework-readability", "clip shot chart"));

        if (Boolean.getBoolean("heron.director.check")) {
            var audio = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=10.3",
                composition.resolve("narration.mp3").toString()).inheritIO().start();
            assertTrue(audio.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, audio.exitValue());
            Path report = work.resolve("check.json");
            var check = new ProcessBuilder("node", root.resolve("data/tools/node_modules/hyperframes/bin/hyperframes.mjs").toString(),
                "check", composition.toString(), "--json").directory(root.resolve("data/tools").toFile()).redirectOutput(report.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            boolean completed = check.waitFor(90, TimeUnit.SECONDS);
            if (!completed) check.destroyForcibly();
            assertTrue(completed, "HyperFrames check 超时");
            String result = Files.readString(report);
            assertEquals(0, check.exitValue(), result);
            var json = JSON.readTree(result.substring(result.indexOf('{')));
            assertTrue(json.path("ok").asBoolean(), result);
            assertTrue(json.path("motion").path("ok").asBoolean(), result);
        }
    }
}
