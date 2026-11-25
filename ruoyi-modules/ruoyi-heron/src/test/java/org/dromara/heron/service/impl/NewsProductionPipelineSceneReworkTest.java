package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@DisplayName("新闻流水线：scene_id 单屏返工")
class NewsProductionPipelineSceneReworkTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCRIPT = """
        {"source_url":"https://example.com/a","fetched_at":"2026-11-03T02:00:00Z",
         "body_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "title":"新一代旗舰手机发布","scenes":[
           {"scene_id":"scene_003","lead":"电池容量提升两成","body":"正文一","duration_s":10,"scene_type":"text"},
           {"scene_id":"scene_001","lead":"影像算法重写","body":"正文二","duration_s":10,"scene_type":"text"},
           {"scene_id":"scene_002","lead":"起售价不变","body":"正文三","duration_s":10,"scene_type":"text"}],
         "closing":"我们会持续跟进。","source_name":"公开报道整理"}
        """;
    private static final String SUBTITLES = """
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
    @DisplayName("按稳定 scene_id 替换，不把 ID 数字当数组下标")
    void replacesByStableSceneId() {
        JsonNode original = JSON.readTree(SCRIPT);
        ObjectNode replacement = (ObjectNode) original.path("scenes").get(1).deepCopy();
        replacement.putObject("visual").put("layout", "quote");

        JsonNode updated = NewsProductionPipeline.replaceSceneBySceneId("scene_001", original, replacement);

        assertEquals(1, NewsProductionPipeline.sceneIndex(updated, "scene_001"));
        assertEquals("quote", updated.path("scenes").get(1).path("visual").path("layout").asString());
        assertTrue(original.path("scenes").get(1).path("visual").isMissingNode(), "原稿不能被原地修改");
        assertEquals(NewsNarrationTiming.spoken(original), NewsNarrationTiming.spoken(updated));
    }

    @Test
    @DisplayName("拒绝未知 ID 与口播改动，时间轴不能被视觉返工悄悄改写")
    void rejectsUnknownSceneAndNarrationChanges() {
        JsonNode original = JSON.readTree(SCRIPT);
        ObjectNode replacement = (ObjectNode) original.path("scenes").get(1).deepCopy();
        replacement.put("body", "改过的口播");

        assertEquals("scene_rework_scene_missing", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.replaceSceneBySceneId("scene_999", original, replacement)).getCode());
        assertEquals("scene_rework_narration_changed", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.replaceSceneBySceneId("scene_001", original, replacement)).getCode());
    }

    @Test
    @DisplayName("拼接滤镜只替换目标视频窗口，原音轨不进入滤镜链")
    void splicesOnlyTheRequestedWindow() {
        String filter = NewsProductionPipeline.sceneSpliceFilter(6, 20, 41.5);
        assertTrue(filter.contains("trim=start=0:end=6.000"), filter);
        assertTrue(filter.contains("trim=start=20.000:end=41.500"), filter);
        assertTrue(filter.contains("concat=n=3:v=1:a=0"), filter);
        assertFalse(filter.contains("0:a"), "原音轨必须由外层命令直接 map，不能重混");
        String first = NewsProductionPipeline.sceneSpliceFilter(0, 10, 41.5);
        String last = NewsProductionPipeline.sceneSpliceFilter(30, 41.5, 41.5);
        assertTrue(first.contains("concat=n=2:v=1:a=0") && !first.contains("[pre]"), first);
        assertTrue(last.contains("concat=n=2:v=1:a=0") && !last.contains("[post]"), last);
    }

    @Test
    @DisplayName("文字闸核对目标屏变量，不拿其它屏的同名变量充数")
    void checksTargetSceneTextVariables() {
        ObjectNode scene = (ObjectNode) JSON.readTree(SCRIPT).path("scenes").get(1).deepCopy();
        scene.put("on_screen_text", "新上屏文字");
        JsonNode vars = JSON.readTree("{\"scene2_text\":\"新上屏文字\",\"scene2_body\":\"正文二\"}");
        Set<String> declared = Set.of("scene2_text", "scene2_body");
        assertTrue(NewsProductionPipeline.sceneTextVariablesMatch(scene, vars, declared, 2));
        assertFalse(NewsProductionPipeline.sceneTextVariablesMatch(scene,
            JSON.readTree("{\"scene2_text\":\"新上屏文字\",\"scene2_body\":\"别的正文\"}"), declared, 2));
        assertFalse(NewsProductionPipeline.sceneTextVariablesMatch(scene,
            JSON.readTree("{\"scene2_body\":\"正文二\"}"), declared, 2), "声明的目标屏文字键不能缺失");
    }

    @Test
    @DisplayName("整片复检要求时长仅有一帧误差、视频起点、音画偏移、帧数与音轨均不变")
    void validatesWholeFilmIntegrity() {
        NewsProductionPipeline.MediaTiming previous = media(0, 0, 1245, 41500);
        NewsProductionPipeline.validateReworkIntegrity(41.5, 41.5, 30, true, previous, previous);
        assertEquals("news_rework_duration_changed", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.validateReworkIntegrity(41.5, 42.5, 30, true, previous, previous)).getCode());
        assertEquals("news_rework_audio_changed", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.validateReworkIntegrity(41.5, 41.5, 30, false, previous, previous)).getCode());
        assertEquals("news_rework_video_offset_changed", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.validateReworkIntegrity(41.5, 41.5, 30, true, previous,
                media(20, 0, 1245, 41500))).getCode());
        assertEquals("news_rework_audio_offset_changed", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.validateReworkIntegrity(41.5, 41.5, 30, true, previous,
                media(0, 20, 1245, 41500))).getCode());
        assertEquals("news_rework_frame_count_changed", assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.validateReworkIntegrity(41.5, 41.5, 30, true, previous,
                media(0, 0, 1244, 41500))).getCode());
    }

    private static NewsProductionPipeline.MediaTiming media(long videoStartMs, long audioStartMs,
                                                             long frames, long audioDurationMs) {
        return new NewsProductionPipeline.MediaTiming(
            new NewsProductionPipeline.VideoTiming(videoStartMs, frames),
            new NewsProductionPipeline.AudioTiming(audioStartMs, audioDurationMs));
    }

    @Test
    @DisplayName("完整返工只调用一次目标屏渲染，不调用 TTS，并重算产物指纹")
    void reworksOneSceneAndRecomputesArtifacts(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        ProductionResultVo result = fixture.pipeline.reworkScene(fixture.run, fixture.request);

        assertEquals(1, fixture.processes.renderCalls, "只允许目标屏渲染一次");
        assertTrue(fixture.processes.renderArguments.stream().anyMatch(args -> args.contains("--start=6.000")
            && args.contains("--end=20.000")), fixture.processes.renderArguments.toString());
        assertEquals(0, fixture.processes.ttsCalls, "返工不得重跑 TTS");
        assertEquals(7.0, fixture.processes.sceneFrameSeek, 0.001, "非首屏必须按单屏片内中点抽帧");
        assertTrue(fixture.processes.spliceArguments.stream().anyMatch(args -> args.contains("-c:a")
            && args.contains("copy") && args.contains("0:a:0")), "原音轨必须逐包复制");

        JsonNode content = JSON.readTree(result.resultJson());
        JsonNode rework = content.path("sceneRework");
        assertEquals("scene_001", rework.path("sceneId").asString());
        assertEquals(1, rework.path("sceneIndex").asInt());
        assertEquals(6000, rework.path("startMs").asLong());
        assertEquals(20000, rework.path("endMs").asLong());
        assertEquals(1, rework.path("renderCalls").asInt());
        assertEquals(0, rework.path("ttsCalls").asInt());
        assertEquals(3L, rework.path("previousVersionId").asLong());
        assertEquals(fixture.previousManifestSha256, rework.path("previousManifestSha256").asString());
        assertTrue(rework.path("artifactFingerprintsRecomputed").asBoolean());
        assertTrue(rework.path("reviewInvalidated").asBoolean());
        assertTrue(rework.path("publishInvalidated").asBoolean());
        assertTrue(content.path("reviewInvalidated").asBoolean());
        assertTrue(content.path("publishInvalidated").asBoolean());
        assertFalse(content.has("editorialReview"), "旧审核结论不得继承");
        assertFalse(content.has("reviewVideoSha"), "旧审核视频指纹不得继承");
        assertFalse(content.has("publishVideoSha"), "旧发布视频指纹不得继承");
        assertEquals(4L, content.path("sourceVersionId").asLong());
        assertEquals(DigestUtil.sha256Hex(JSON.writeValueAsString(result.artifacts())),
            content.path("artifactManifestSha256").asString());
        assertEquals(2, rework.path("unchangedSceneCount").asInt());
        assertEquals(41500, content.path("durationMs").asLong());
        assertEquals(0, rework.path("durationDeltaMs").asLong());
        assertTrue(rework.path("audioHashMatched").asBoolean());
        assertEquals(0, rework.path("videoStartMs").asInt());
        assertEquals(1245, rework.path("videoFrameCount").asInt());
        assertEquals(0, rework.path("audioVideoOffsetMs").asInt());
        assertEquals("quote", content.path("script").path("scenes").get(1).path("visual").path("layout").asString());
        assertNotEquals(fixture.previousVideo.sha256(), artifact(result, "mp4").sha256());
        assertEquals(DigestUtil.sha256Hex("new-video".getBytes(StandardCharsets.UTF_8)), artifact(result, "mp4").sha256());
        assertEquals(artifact(result, "mp4").sha256(), rework.path("videoSha256").asString());
        assertEquals(artifact(result, "contact_sheet").sha256(), rework.path("contactSheetSha256").asString());
        assertFalse(rework.path("videoSha256").asString().isBlank());
        assertFalse(rework.path("contactSheetSha256").asString().isBlank());
        assertEquals(fixture.previousSubtitles.sha256(), artifact(result, "subtitles").sha256(), "字幕字节必须原样保留");
        assertEquals(Set.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest"),
            result.artifacts().stream().map(ProductionArtifactVo::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(findGate(content.path("checks"), "aigc_metadata").path("passed").asBoolean());
        for (String gate : List.of("schema", "evidence", "audio", "audio_mix", "subtitles", "render", "decode",
            "visual", "aigc_metadata", "aigc_visible", "assets")) {
            assertTrue(findGate(content.path("checks"), gate).path("passed").asBoolean(), "整片闸未复检：" + gate);
        }
        assertTrue(findGate(content.path("checks"), "scene_render").path("measured").path("renderCalls").asInt() == 1);
        assertTrue(findGate(content.path("checks"), "rework_integrity").path("measured").path("audioHashMatched").asBoolean());
    }

    @Test
    @DisplayName("旧片未烧字幕时，返工拼接后补烧全片且不重跑 TTS")
    void burnsWholeReworkWhenPreviousWasNotBurned(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root, null, true, false, false);

        ProductionResultVo result = fixture.pipeline.reworkScene(fixture.run, fixture.request);

        JsonNode content = JSON.readTree(result.resultJson());
        assertTrue(content.path("subtitlesBurned").asBoolean());
        assertTrue(findGate(content.path("checks"), "subtitles_burn").path("passed").asBoolean());
        assertEquals(0, fixture.processes.ttsCalls);
        assertTrue(fixture.processes.burnArguments.stream().anyMatch(args -> args.getLast().equals("final-burned.mp4")));
        assertTrue(fixture.processes.burnArguments.stream().noneMatch(args -> args.getLast().equals("scene-burned.mp4")));
    }

    @Test
    @DisplayName("旧片已烧字幕时，返工只烧替换屏，避免旧字幕二次叠加")
    void burnsOnlyReplacementSceneWhenPreviousWasBurned(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root, null, true, false, true);

        ProductionResultVo result = fixture.pipeline.reworkScene(fixture.run, fixture.request);

        JsonNode content = JSON.readTree(result.resultJson());
        assertTrue(content.path("subtitlesBurned").asBoolean());
        assertTrue(findGate(content.path("checks"), "subtitles_burn").path("passed").asBoolean());
        assertTrue(fixture.processes.burnArguments.stream().anyMatch(args -> args.getLast().equals("scene-burned.mp4")));
        assertTrue(fixture.processes.burnArguments.stream().noneMatch(args -> args.getLast().equals("final-burned.mp4")));
        assertTrue(fixture.processes.spliceArguments.stream().anyMatch(args -> args.contains("scene-burned.mp4")));
        String ass = Files.readString(fixture.artifacts.prepareWorkDirectory(fixture.run).resolve("composition/scene.ass"));
        assertTrue(ass.contains("影像算法重写") && ass.contains("正文二"));
        assertTrue(!ass.contains("电池容量提升两成") && !ass.contains("起售价与上一代持平"));
    }

    @Test
    @DisplayName("带封面的上一版可返工，新封面地址不会被旧字段清理误删")
    void reworksCoveredVersionAndReplacesCoverUrl(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root, null, false, true, false);

        ProductionResultVo result = fixture.pipeline.reworkScene(fixture.run, fixture.request);

        JsonNode content = JSON.readTree(result.resultJson());
        assertEquals(Set.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest", "cover", "cover_thumb"),
            result.artifacts().stream().map(ProductionArtifactVo::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(findGate(content.path("checks"), "cover").path("passed").asBoolean());
        assertTrue(content.path("coverUrl").asString().startsWith("/heron/newsWork/7/versions/4/preview/"));
        assertNotEquals("/old-cover", content.path("coverUrl").asString());
    }

    @Test
    @DisplayName("非规范 JSON 的上一版 script 审计哈希等于真实 artifact SHA，并继承 hookType")
    void auditsOriginalScriptArtifactHashAndInheritsHookType(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        List<ProductionArtifactVo> artifacts = new ArrayList<>(fixture.previousArtifacts);
        int scriptIndex = 0;
        for (int index = 0; index < artifacts.size(); index++) {
            if ("script".equals(artifacts.get(index).kind())) {
                scriptIndex = index;
            }
        }
        ProductionArtifactVo oldScript = artifacts.get(scriptIndex);
        byte[] pretty = ("\n  " + SCRIPT.replace(",", ",\n  ") + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("data").resolve(oldScript.storageKey()), pretty);
        artifacts.set(scriptIndex, new ProductionArtifactVo(oldScript.artifactId(), "script", oldScript.storageKey(),
            pretty.length, DigestUtil.sha256Hex(pretty), "application/json"));
        String manifestSha = DigestUtil.sha256Hex(JSON.writeValueAsString(artifacts));
        ObjectNode previous = (ObjectNode) JSON.readTree(fixture.previousResultJson);
        previous.put("artifactManifestSha256", manifestSha);
        previous.putObject("contentProfile").put("hookType", "数字反差");
        var request = new SceneReworkRequest("scene_001", fixture.request.replacementScene(), 3L, manifestSha,
            artifacts, JSON.writeValueAsString(previous));

        ProductionResultVo result = fixture.pipeline.reworkScene(fixture.run, request);

        JsonNode content = JSON.readTree(result.resultJson());
        assertEquals(DigestUtil.sha256Hex(pretty), content.path("sceneRework").path("previousScriptSha256").asString());
        assertEquals("数字反差", content.path("contentProfile").path("hookType").asString());
    }

    @Test
    @DisplayName("畸形 scenes 在场景映射前返回业务错误，不触发渲染")
    void rejectsMalformedScenesBeforeRender(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        ObjectNode previous = (ObjectNode) JSON.readTree(fixture.previousResultJson);
        previous.set("script", JSON.readTree("{\"scenes\":{}}"));
        SceneReworkRequest request = new SceneReworkRequest("scene_001", fixture.request.replacementScene(), 3L,
            fixture.previousManifestSha256, fixture.previousArtifacts, JSON.writeValueAsString(previous));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> fixture.pipeline.reworkScene(fixture.run, request));

        assertEquals("scene_rework_source_invalid", error.getCode());
        assertEquals(0, fixture.processes.renderCalls);
    }

    @Test
    @DisplayName("不完整 manifest 在渲染与任何定稿前失败")
    void rejectsIncompleteManifestBeforeRender(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        List<ProductionArtifactVo> incomplete = List.of(fixture.previousVideo, fixture.previousSubtitles);
        String manifestSha = DigestUtil.sha256Hex(JSON.writeValueAsString(incomplete));
        ObjectNode previous = (ObjectNode) JSON.readTree(fixture.previousResultJson);
        previous.put("artifactManifestSha256", manifestSha);
        SceneReworkRequest request = new SceneReworkRequest("scene_001", fixture.request.replacementScene(), 3L,
            manifestSha, incomplete, JSON.writeValueAsString(previous));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> fixture.pipeline.reworkScene(fixture.run, request));

        assertEquals("scene_rework_source_invalid", error.getCode());
        assertEquals(0, fixture.processes.renderCalls);
        assertTrue(!Files.exists(fixture.dataRoot.resolve("videos/7/v4/a1-" + fixture.run.runToken())));
    }

    @Test
    @DisplayName("manifest 指纹不匹配时拒绝使用上一版产物")
    void rejectsManifestFingerprintMismatch(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        SceneReworkRequest request = new SceneReworkRequest("scene_001", fixture.request.replacementScene(), 3L,
            "0".repeat(64), fixture.previousArtifacts, fixture.previousResultJson);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> fixture.pipeline.reworkScene(fixture.run, request));

        assertEquals("scene_rework_manifest_mismatch", error.getCode());
        assertEquals(0, fixture.processes.renderCalls);
    }

    @Test
    @DisplayName("上一版结果 script 必须与已校验 script 产物完全相等，伪造快照在渲染前失败")
    void rejectsForgedPreviousScriptBeforeRender(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        ObjectNode previous = (ObjectNode) JSON.readTree(fixture.previousResultJson);
        ((ObjectNode) previous.path("script")).put("title", "调用方伪造的标题");
        SceneReworkRequest request = new SceneReworkRequest("scene_001", fixture.request.replacementScene(), 3L,
            fixture.previousManifestSha256, fixture.previousArtifacts, JSON.writeValueAsString(previous));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> fixture.pipeline.reworkScene(fixture.run, request));

        assertEquals("scene_rework_script_mismatch", error.getCode());
        assertEquals(0, fixture.processes.renderCalls);
        assertTrue(!Files.exists(fixture.dataRoot.resolve("videos/7/v4/a1-" + fixture.run.runToken())));
    }

    @Test
    @DisplayName("定稿中途失败回收已定稿产物，不留下半份结果")
    void cleansPartialFinalization(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        doThrow(new HeronBusinessException(409, "test_finalize_failed", "模拟联络表定稿失败"))
            .when(fixture.artifacts).finalizeFile(eq(fixture.run), eq("contact_sheet"), any(Path.class), eq("image/png"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> fixture.pipeline.reworkScene(fixture.run, fixture.request));

        assertEquals("test_finalize_failed", error.getCode());
        Path attemptDirectory = fixture.dataRoot.resolve("videos/7/v4/a1-" + fixture.run.runToken());
        if (Files.exists(attemptDirectory)) {
            try (var files = Files.list(attemptDirectory)) {
                assertEquals(0, files.count(), "失败尝试不得残留已定稿文件");
            }
        }
    }

    @Test
    @DisplayName("新屏黑场或空白帧在拼接前失败，不污染最终产物")
    void rejectsBlankSceneBeforeSplice(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        fixture.processes.blankScene = true;
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> fixture.pipeline.reworkScene(fixture.run, fixture.request));
        assertEquals("news_scene_visual_failed", error.getCode());
        assertEquals(0, fixture.processes.spliceCalls, "失败场景不能进入拼接");
        Path attemptDirectory = fixture.dataRoot.resolve("videos/7/v4/a1-" + fixture.run.runToken());
        assertTrue(!Files.exists(attemptDirectory), "闸失败后不得创建定稿目录");
    }

    private static ProductionArtifactVo artifact(ProductionResultVo result, String kind) {
        return result.artifacts().stream().filter(item -> kind.equals(item.kind())).findFirst().orElseThrow();
    }

    private static JsonNode findGate(JsonNode checks, String name) {
        for (JsonNode gate : checks) {
            if (name.equals(gate.path("gate").asString())) {
                return gate;
            }
        }
        throw new AssertionError("缺少闸 " + name);
    }

    static final class Fixture {
        final Path dataRoot;
        final ProductionRunVo run;
        final SceneReworkRequest request;
        final List<ProductionArtifactVo> previousArtifacts;
        final ProductionArtifactVo previousVideo;
        final ProductionArtifactVo previousSubtitles;
        final String previousResultJson;
        final String previousManifestSha256;
        final ProductionArtifactService artifacts;
        final NewsProductionPipeline pipeline;
        final RecordingProcesses processes;

        private Fixture(Path root) throws Exception {
            this(root, null, false, false, false);
        }

        Fixture(Path root, org.dromara.heron.service.IMediaAssetService mediaAssets) throws Exception {
            this(root, mediaAssets, false, false, false);
        }

        Fixture(Path root, org.dromara.heron.service.IMediaAssetService mediaAssets,
                boolean burnSubtitles, boolean withCover, boolean previousBurned) throws Exception {
            dataRoot = root.resolve("data");
            Path templateRoot = root.resolve("templates");
            Path template = templateRoot.resolve("news-9x16");
            Files.createDirectories(template.resolve("fonts"));
            Files.writeString(template.resolve("fonts/NotoSansSC-Regular.otf"), "font");
            Files.writeString(template.resolve("assets-manifest.json"), "{}");
            Files.writeString(template.resolve("template.json"), "{\"supportedSceneTypes\":[\"text\",\"image\"]}");
            Files.writeString(template.resolve("template.config.json"),
                "{\"style\":\"article\",\"layout\":\"news-9x16\",\"width\":1080,\"height\":1920}");
            Path tokens = Files.createDirectories(templateRoot.resolve("_shared/tokens"));
            Files.writeString(tokens.resolve("article.css"), """
                :root { --font-body: "Noto Sans SC", sans-serif; --fs-body: 38px;
                        --fg: #ffffff; --bg: #000000; --pad-frame: 84px; }
                """);
            Files.writeString(template.resolve("index.motion.json"), "{\"assertions\":[]}");
            Files.writeString(template.resolve("index.html"), """
                <html data-composition-variables='[
                  {"id":"title","type":"string","default":""},
                  {"id":"closing","type":"string","default":""},
                  {"id":"source_name","type":"string","default":""},
                  {"id":"scene_count","type":"number","default":3},
                  {"id":"scene1_lead","type":"string","default":""},
                  {"id":"scene1_body","type":"string","default":""},
                  {"id":"scene1_image","type":"string","default":""},
                  {"id":"scene1_type","type":"string","default":""},
                  {"id":"scene1_beat","type":"string","default":""},
                  {"id":"scene1_text","type":"string","default":""},
                  {"id":"scene2_lead","type":"string","default":""},
                  {"id":"scene2_body","type":"string","default":""},
                  {"id":"scene2_image","type":"string","default":""},
                  {"id":"scene2_type","type":"string","default":""},
                  {"id":"scene2_beat","type":"string","default":""},
                  {"id":"scene2_text","type":"string","default":""},
                  {"id":"scene3_lead","type":"string","default":""},
                  {"id":"scene3_body","type":"string","default":""},
                  {"id":"scene3_image","type":"string","default":""},
                  {"id":"scene3_type","type":"string","default":""},
                  {"id":"scene3_beat","type":"string","default":""},
                  {"id":"scene3_text","type":"string","default":""}]'>
                <body><div id="root" data-start="0" data-duration="45">
                <audio id="narration" data-start="0" data-duration="45"></audio>
                <header id="title-card" data-start="0" data-duration="45"></header>
                <section id="chart-slot" data-start="0" data-duration="45"></section>
                <section id="scene-1" data-start="0" data-duration="10"></section>
                <section id="scene-2" data-start="10" data-duration="10"></section>
                <section id="scene-3" data-start="20" data-duration="10"></section>
                <section id="scene-4" data-start="30" data-duration="5"></section>
                <section id="scene-5" data-start="35" data-duration="5"></section>
                <section id="scene-6" data-start="40" data-duration="5"></section>
                <aside id="closing-slot" data-start="30" data-duration="15"></aside>
                </div></body></html>
                """);
            Path cli = root.resolve("hyperframes.mjs");
            Files.writeString(cli, "// test CLI entry");

            HeronProductionProperties properties = new HeronProductionProperties();
            properties.setDataRoot(dataRoot);
            properties.setNewsTemplate(templateRoot);
            properties.setHyperframes(cli.toString());
            properties.setGuardEnabled(false);
            artifacts = spy(new ProductionArtifactService(properties, null));
            processes = new RecordingProcesses(properties, artifacts);
            HeronNewsProperties newsProperties = new HeronNewsProperties();
            newsProperties.getSubtitles().setBurn(burnSubtitles);
            newsProperties.getCover().setEnabled(withCover);

            String oldToken = UUID.randomUUID().toString();
            Path oldDirectory = dataRoot.resolve("videos/7/v3/a1-" + oldToken);
            Files.createDirectories(oldDirectory);
            Path oldVideo = oldDirectory.resolve(UUID.randomUUID() + ".mp4");
            Files.writeString(oldVideo, "old-video");
            Path oldSubtitles = oldDirectory.resolve(UUID.randomUUID() + ".json");
            Files.writeString(oldSubtitles, NewsProductionPipeline.subtitleJson(SUBTITLES));
            Path oldContact = oldDirectory.resolve(UUID.randomUUID() + ".png");
            Files.writeString(oldContact, "old-contact");
            Path oldScript = oldDirectory.resolve(UUID.randomUUID() + ".json");
            Files.writeString(oldScript, SCRIPT);
            Path oldChecks = oldDirectory.resolve(UUID.randomUUID() + ".json");
            Files.writeString(oldChecks, previousBurned
                ? "[{\"gate\":\"subtitles_burn\",\"inputLocator\":\"subs.vtt#final.mp4\",\"passed\":true,\"reason\":\"已烧录\",\"measured\":{\"cues\":7}}]"
                : "[]");
            Path oldManifest = oldDirectory.resolve(UUID.randomUUID() + ".json");
            Files.writeString(oldManifest, "{\"files\":[{\"path\":\"scene3.png\",\"sha256\":\""
                + "a".repeat(64) + "\",\"bytes\":4}]}");
            previousVideo = oldArtifact(oldVideo, "mp4", "video/mp4");
            previousSubtitles = oldArtifact(oldSubtitles, "subtitles", "application/json");
            previousArtifacts = new ArrayList<>(List.of(previousVideo, oldArtifact(oldContact, "contact_sheet", "image/png"),
                oldArtifact(oldScript, "script", "application/json"), previousSubtitles,
                oldArtifact(oldChecks, "checks", "application/json"),
                oldArtifact(oldManifest, "asset_manifest", "application/json")));
            if (withCover) {
                Path oldCover = oldDirectory.resolve(UUID.randomUUID() + ".png");
                Path oldThumb = oldDirectory.resolve(UUID.randomUUID() + ".png");
                Files.write(oldCover, RecordingProcesses.image(1080, 1920, false));
                Files.write(oldThumb, RecordingProcesses.image(1080, 1080, false));
                previousArtifacts.add(oldArtifact(oldCover, "cover", "image/png"));
                previousArtifacts.add(oldArtifact(oldThumb, "cover_thumb", "image/png"));
            }
            previousManifestSha256 = DigestUtil.sha256Hex(JSON.writeValueAsString(previousArtifacts));

            String input = JSON.writeValueAsString(Map.of("kind", "article", "assets", Map.of(
                "templateKey", "news-9x16", "voiceKey", "zh-CN-XiaoxiaoNeural",
                "assetManifestSha256", DigestUtil.sha256Hex(template.resolve("assets-manifest.json").toFile()),
                "fontSha256", DigestUtil.sha256Hex(template.resolve("fonts/NotoSansSC-Regular.otf").toFile()))));
            run = new ProductionRunVo(99L, ProductionBizType.news_video, 7L, 4L, 1,
                UUID.randomUUID().toString(), input, null);
            JsonNode replacement = JSON.readTree(SCRIPT).path("scenes").get(1).deepCopy();
            ((ObjectNode) replacement).putObject("visual").put("layout", "quote");
            ObjectNode previousResult = JSON.createObjectNode();
            previousResult.set("script", JSON.readTree(SCRIPT));
            previousResult.put("durationMs", 41500);
            previousResult.set("editorialReview", JSON.readTree("{\"verdict\":\"PASS\"}"));
            previousResult.put("sourceVersionId", 3L);
            previousResult.put("artifactManifestSha256", previousManifestSha256);
            previousResult.put("reviewVideoSha", "old-review");
            previousResult.put("publishVideoSha", "old-publish");
            if (withCover) {
                previousResult.put("coverUrl", "/old-cover");
            }
            if (previousBurned) {
                previousResult.put("subtitlesBurned", true);
            }
            previousResultJson = JSON.writeValueAsString(previousResult);
            request = new SceneReworkRequest("scene_001", replacement, 3L, previousManifestSha256,
                previousArtifacts, previousResultJson);
            pipeline = new NewsProductionPipeline(null, null, processes, artifacts, properties,
                (ignoredRun, stage, progress) -> true, null, null, null, null, null, null,
                mock(FrozenContextService.class), mediaAssets, newsProperties);
        }

        private ProductionArtifactVo oldArtifact(Path file, String kind, String mediaType) throws Exception {
            String id = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            return new ProductionArtifactVo(id, kind, dataRoot.relativize(file).toString().replace('\\', '/'),
                Files.size(file), DigestUtil.sha256Hex(Files.readAllBytes(file)), mediaType);
        }
    }

    private static final class RecordingProcesses extends ProductionProcessService {
        private int renderCalls;
        private int ttsCalls;
        private int spliceCalls;
        private boolean blankScene;
        private double sceneStart;
        private double sceneEnd;
        private double sceneFrameSeek = -1;
        private final List<List<String>> renderArguments = new ArrayList<>();
        private final List<List<String>> spliceArguments = new ArrayList<>();
        private final List<List<String>> burnArguments = new ArrayList<>();

        private RecordingProcesses(HeronProductionProperties properties, ProductionArtifactService artifacts) {
            super(properties, artifacts);
        }

        @Override
        public ProductionProcessVo run(ProductionRunVo run, ProductionProcessBo command) {
            List<String> args = command.arguments();
            Path work = command.workDirectory();
            try {
                return switch (command.tool()) {
                    case "python" -> {
                        ttsCalls++;
                        yield failure("返工不得调用 TTS");
                    }
                    case "node" -> node(work, args);
                    case "ffprobe" -> probe(args);
                    case "ffmpeg" -> ffmpeg(work, args);
                    default -> failure("未预期的工具");
                };
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private ProductionProcessVo node(Path work, List<String> args) throws Exception {
            if (args.contains("check")) {
                return ok("""
                    {"ok":true,"lint":{"errorCount":0,"warningCount":0},
                     "runtime":{"errorCount":0,"warningCount":0},"layout":{"errorCount":0,"warningCount":0},
                     "contrast":{"errorCount":0,"warningCount":0},"craft":{"errorCount":0,"warningCount":0},
                     "audio":{"errorCount":0,"warningCount":0},"motion":{"ok":true,"errorCount":0,"samples":3}}
                    """);
            }
            renderCalls++;
            renderArguments.add(List.copyOf(args));
            sceneStart = Double.parseDouble(args.stream().filter(item -> item.startsWith("--start=")).findFirst()
                .orElseThrow().substring("--start=".length()));
            sceneEnd = Double.parseDouble(args.stream().filter(item -> item.startsWith("--end=")).findFirst()
                .orElseThrow().substring("--end=".length()));
            Files.writeString(work.resolve("scene.mp4"), "new-scene");
            return ok("");
        }

        private ProductionProcessVo probe(List<String> args) {
            if (args.contains("format_tags=AIGC")) {
                return ok("内容由 AI 生成");
            }
            String file = args.get(args.size() - 1);
            double seconds = "scene.mp4".equals(file) || "scene-burned.mp4".equals(file)
                ? sceneEnd - sceneStart : 41.5;
            if (args.contains("-show_streams")) {
                return ok("""
                    {"streams":[{"codec_type":"video","width":1080,"height":1920,"r_frame_rate":"30/1",
                                 "start_time":"0.000","nb_frames":%d},
                                {"codec_type":"audio","start_time":"0.000","duration":"%s"}],
                     "format":{"duration":%s}}
                    """.formatted(Math.round(seconds * 30), fmt(seconds), fmt(seconds)));
            }
            return ok("duration=" + fmt(seconds));
        }

        private ProductionProcessVo ffmpeg(Path work, List<String> args) throws Exception {
            if (args.contains("0:a:0") && args.contains("copy") && args.contains("-f")) {
                return ok("SHA256=same-audio-packets");
            }
            if (String.join(" ", args).contains("blackdetect")) {
                return ok("");
            }
            if (String.join(" ", args).contains("loudnorm")) {
                return new ProductionProcessVo(0, false, false, "",
                    "{\"input_i\":\"-16.0\",\"input_tp\":\"-2.0\",\"input_lra\":\"7.0\",\"input_thresh\":\"-26.0\"}");
            }
            if (args.contains("scene-frame.png")) {
                sceneFrameSeek = Double.parseDouble(args.get(args.indexOf("-ss") + 1));
                if (sceneFrameSeek < 0 || sceneFrameSeek >= sceneEnd - sceneStart) {
                    return failure("抽帧 seek 超出单屏片长");
                }
                Files.write(work.resolve("scene-frame.png"), image(360, 640, blankScene));
                return ok("");
            }
            if (args.contains("contact.png")) {
                Files.write(work.resolve("contact.png"), sheet());
                return ok("");
            }
            if (args.get(args.size() - 1).equals("composition/narration.mp3")) {
                Files.writeString(work.resolve("composition/narration.mp3"), "render-audio");
                return ok("");
            }
            String output = args.get(args.size() - 1);
            if ("scene-burned.mp4".equals(output) || "final-burned.mp4".equals(output)) {
                burnArguments.add(List.copyOf(args));
                Files.writeString(work.resolve(output), "burned-video");
                return ok("");
            }
            if ("subtitle-raw.png".equals(output) || "subtitle-final.png".equals(output)) {
                Files.write(work.resolve(output), subtitleFrame("subtitle-final.png".equals(output)));
                return ok("");
            }
            if (output.startsWith("cover-candidate-") || "cover.png".equals(output) || "cover-thumb.png".equals(output)) {
                int width = "cover.png".equals(output) ? 1080 : output.startsWith("cover-candidate-") ? 360 : 1080;
                int height = "cover.png".equals(output) ? 1920 : output.startsWith("cover-candidate-") ? 640 : 1080;
                Files.write(work.resolve(output), image(width, height, false));
                return ok("");
            }
            if (output.equals("final.mp4")) {
                spliceCalls++;
                spliceArguments.add(List.copyOf(args));
                Files.writeString(work.resolve("final.mp4"), "new-video");
                return ok("");
            }
            return ok("");
        }

        private static byte[] image(int width, int height, boolean blank) throws Exception {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, blank ? 0x101010 : ((x * 17 + y * 31) & 0xFFFFFF));
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }

        private static byte[] subtitleFrame(boolean burned) throws Exception {
            BufferedImage frame = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = frame.createGraphics();
            graphics.setColor(java.awt.Color.BLACK);
            graphics.fillRect(0, 0, 1080, 1920);
            if (burned) {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(100, 1000, 800, 100);
            }
            graphics.dispose();
            var out = new java.io.ByteArrayOutputStream();
            ImageIO.write(frame, "png", out);
            return out.toByteArray();
        }

        private static byte[] sheet() throws Exception {
            BufferedImage sheet = new BufferedImage(1080, 640, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 3; column++) {
                    for (int y = 0; y < 320; y++) {
                        for (int x = 0; x < 360; x++) {
                            sheet.setRGB(column * 360 + x, row * 320 + y,
                                (x * 17 + y * 31 + row * 101 + column * 211) & 0xFFFFFF);
                        }
                    }
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            ImageIO.write(sheet, "png", out);
            return out.toByteArray();
        }

        private static String fmt(double value) {
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }

        private static ProductionProcessVo ok(String stdout) {
            return new ProductionProcessVo(0, false, false, stdout, "");
        }

        private static ProductionProcessVo failure(String stderr) {
            return new ProductionProcessVo(1, false, false, "", stderr);
        }
    }
}
