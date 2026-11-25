package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 真 HyperFrames + ffmpeg + ffprobe 的非首屏返工；默认 tag=render，需显式运行。 */
@Tag("render")
@DisplayName("场景返工真渲染：非首屏区间、指纹与音画时间轴")
class NewsProductionPipelineSceneReworkRenderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCRIPT = """
        {"source_url":"https://example.com/tech/release","fetched_at":"2026-11-03T02:00:00Z",
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
    @DisplayName("只重渲 scene_001 的 6–20 秒区间，并保留 41.5 秒音轨与 1245 帧")
    void rendersOnlyNonFirstSceneWithRealTools() throws Exception {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path repository = Files.isDirectory(current.resolve("data/templates"))
            ? current : Path.of("..", "..").toAbsolutePath().normalize();
        Path templateRoot = repository.resolve("data/templates");
        Path template = templateRoot.resolve("news-9x16");
        Path cli = repository.resolve("data/tools/node_modules/hyperframes/bin/hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);
        assertTrue(Files.isRegularFile(cli), "缺少 HyperFrames 入口：" + cli);

        Path dataRoot = repository.resolve("data/it-render/v2-scene-rework-" + UUID.randomUUID());
        System.out.println("V2_REWORK_DATA_ROOT=" + dataRoot.toAbsolutePath());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setNewsTemplate(templateRoot);
        properties.setHyperframes(cli.toString());
        properties.setNode(System.getenv().getOrDefault("HERON_TEST_NODE", "node"));
        properties.setGuardEnabled(false);
        ProductionArtifactService artifacts = new ProductionArtifactService(properties, null);
        RecordingProcesses processes = new RecordingProcesses(properties, artifacts, dataRoot);
        NewsProductionPipeline pipeline = new NewsProductionPipeline(null, null, processes, artifacts, properties,
            (run, stage, progress) -> true, null, null, null, null, null, null, mock(FrozenContextService.class));

        ProductionRunVo previousRun = new ProductionRunVo(7300L, ProductionBizType.news_explainer, 10L, 3L, 1,
            UUID.randomUUID().toString(), "{}");
        Path previousWork = artifacts.prepareWorkDirectory(previousRun);
        ProductionProcessVo created = processes.run(previousRun, new ProductionProcessBo("ffmpeg", List.of(
            "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=1080x1920:rate=30:duration=41.5",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=41.5",
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
            "-af", "loudnorm=I=-16:TP=-2:LRA=7", "-t", "41.500", "-movflags", "+use_metadata_tags",
            "-metadata", "AIGC=内容由 AI 生成", "previous-source.mp4"), previousWork, Duration.ofMinutes(5)));
        assertEquals(0, created.exitCode(), created.stderr());
        ProductionArtifactVo previousVideo = artifacts.finalizeFile(previousRun, "mp4",
            previousWork.resolve("previous-source.mp4"), "video/mp4");
        ProductionArtifactVo previousSubtitles = finalizeJson(artifacts, previousRun, "subtitles",
            NewsProductionPipeline.subtitleJson(SUBTITLES));
        ProductionArtifactVo previousContact = finalizeJson(artifacts, previousRun, "contact_sheet", "old-contact");
        ProductionArtifactVo previousScript = finalizeJson(artifacts, previousRun, "script", SCRIPT);
        ProductionArtifactVo previousChecks = finalizeJson(artifacts, previousRun, "checks", "[]");
        ProductionArtifactVo previousManifest = finalizeJson(artifacts, previousRun, "asset_manifest", "{}");
        List<ProductionArtifactVo> previousArtifacts = List.of(previousVideo, previousContact, previousScript,
            previousSubtitles, previousChecks, previousManifest);
        String manifestSha = DigestUtil.sha256Hex(JSON.writeValueAsString(previousArtifacts));

        ObjectNode previousResult = JSON.createObjectNode();
        previousResult.set("script", JSON.readTree(SCRIPT));
        previousResult.put("durationMs", 41_500);
        previousResult.put("sourceVersionId", 3L);
        previousResult.put("artifactManifestSha256", manifestSha);
        previousResult.putObject("editorialReview").put("verdict", "PASS");
        previousResult.put("reviewVideoSha", "stale-review");
        previousResult.put("publishVideoSha", "stale-publish");
        JsonNode replacement = JSON.readTree(SCRIPT).path("scenes").get(1).deepCopy();
        ((ObjectNode) replacement).putObject("visual").put("layout", "quote");
        SceneReworkRequest request = new SceneReworkRequest("scene_001", replacement, 3L, manifestSha,
            previousArtifacts, JSON.writeValueAsString(previousResult));

        String input = JSON.writeValueAsString(Map.of("kind", "article", "assets", Map.of(
            "templateKey", "news-9x16", "voiceKey", "zh-CN-XiaoxiaoNeural",
            "assetManifestSha256", DigestUtil.sha256Hex(template.resolve("assets-manifest.json").toFile()),
            "fontSha256", DigestUtil.sha256Hex(templateRoot.getParent().resolve("fonts/NotoSansSC-Regular.otf").toFile()))));
        ProductionRunVo run = new ProductionRunVo(7301L, ProductionBizType.news_explainer, 10L, 4L, 1,
            UUID.randomUUID().toString(), input);
        ProductionResultVo result = pipeline.reworkScene(run, request);
        JsonNode content = JSON.readTree(result.resultJson());
        JsonNode rework = content.path("sceneRework");

        assertEquals(1, processes.renderArguments.size(), "真实链路只能调用一次区间渲染");
        List<String> render = processes.renderArguments.get(0);
        assertTrue(render.contains("--start=6.000") && render.contains("--end=20.000"), render.toString());
        assertEquals("7.000", processes.frameSeek, "14 秒单屏片应抽 7 秒中点帧");
        assertEquals(1, rework.path("renderCalls").asInt());
        assertEquals(0, rework.path("ttsCalls").asInt());
        assertEquals(3L, rework.path("previousVersionId").asLong());
        assertEquals(manifestSha, rework.path("previousManifestSha256").asString());
        assertEquals(byKind(result, "mp4").sha256(), rework.path("videoSha256").asString());
        assertEquals(byKind(result, "contact_sheet").sha256(), rework.path("contactSheetSha256").asString());
        assertFalse(rework.path("videoSha256").asString().isBlank());
        assertFalse(content.has("editorialReview"));
        assertFalse(content.has("reviewVideoSha"));
        assertFalse(content.has("publishVideoSha"));
        assertTrue(content.path("reviewInvalidated").asBoolean());
        assertTrue(content.path("publishInvalidated").asBoolean());
        assertTrue(rework.path("audioHashMatched").asBoolean());
        assertEquals(0, rework.path("durationDeltaMs").asInt());
        assertEquals(1245, rework.path("videoFrameCount").asInt());
        assertEquals(0, rework.path("videoStartMs").asInt());
        assertEquals(0, rework.path("audioVideoOffsetMs").asInt());

        Path work = artifacts.prepareWorkDirectory(run);
        JsonNode sceneProbe = probe(processes, run, work, "scene.mp4");
        JsonNode finalProbe = probe(processes, run, work,
            dataRoot.resolve(byKind(result, "mp4").storageKey()).toString());
        assertEquals(14.0, sceneProbe.path("format").path("duration").asDouble(), 0.05);
        assertEquals(420, sceneProbe.path("streams").get(0).path("nb_frames").asInt());
        assertEquals(41.5, finalProbe.path("format").path("duration").asDouble(), 0.05);
        assertEquals(1245, finalProbe.path("streams").get(0).path("nb_frames").asInt());

        Path preview = dataRoot.resolve("scene-rework-preview.mp4");
        Files.copy(dataRoot.resolve(byKind(result, "mp4").storageKey()), preview, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dataRoot.resolve(byKind(result, "contact_sheet").storageKey()),
            dataRoot.resolve("scene-rework-contact.png"), StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("renderArguments", processes.renderArguments);
        evidence.put("frameSeek", processes.frameSeek);
        evidence.put("sceneProbe", sceneProbe);
        evidence.put("finalProbe", finalProbe);
        evidence.put("sceneRework", rework);
        evidence.put("preview", preview.toAbsolutePath().toString());
        Path evidenceFile = dataRoot.resolve("render-evidence.json");
        Files.writeString(evidenceFile, JSON.writeValueAsString(evidence), StandardCharsets.UTF_8);
        System.out.println("V2_REWORK_EVIDENCE=" + evidenceFile.toAbsolutePath());
        System.out.println("V2_REWORK_PREVIEW=" + preview.toAbsolutePath());
    }

    private static ProductionArtifactVo finalizeJson(ProductionArtifactService artifacts, ProductionRunVo run,
                                                       String kind, String json) throws Exception {
        Path work = artifacts.prepareWorkDirectory(run);
        Path file = work.resolve(kind + "-source." + ("contact_sheet".equals(kind) ? "png" : "json"));
        Files.writeString(file, json, StandardCharsets.UTF_8);
        String mediaType = switch (kind) {
            case "contact_sheet" -> "image/png";
            case "subtitles" -> "application/json";
            default -> "application/json";
        };
        return artifacts.finalizeFile(run, kind, file, mediaType);
    }

    private static JsonNode probe(RecordingProcesses processes, ProductionRunVo run, Path work, String file) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_format", "-show_streams", "-of", "json", file), work,
            Duration.ofSeconds(30)));
        assertEquals(0, result.exitCode(), result.stderr());
        return JSON.readTree(result.stdout());
    }

    private static ProductionArtifactVo byKind(ProductionResultVo result, String kind) {
        return result.artifacts().stream().filter(item -> kind.equals(item.kind())).findFirst().orElseThrow();
    }

    private static final class RecordingProcesses extends ProductionProcessService {
        private final Path dataRoot;
        private final List<List<String>> renderArguments = new ArrayList<>();
        private String frameSeek;

        private RecordingProcesses(HeronProductionProperties properties, ProductionArtifactService artifacts,
                                    Path dataRoot) {
            super(properties, artifacts);
            this.dataRoot = dataRoot;
        }

        @Override
        public ProductionProcessVo run(ProductionRunVo run, ProductionProcessBo command) {
            boolean render = "node".equals(command.tool()) && command.arguments().contains("render");
            if (render) {
                renderArguments.add(List.copyOf(command.arguments()));
            }
            if ("ffmpeg".equals(command.tool()) && command.arguments().contains("scene-frame.png")) {
                frameSeek = command.arguments().get(command.arguments().indexOf("-ss") + 1);
            }
            ProductionProcessVo result = super.run(run, command);
            if (render) {
                try {
                    Files.writeString(dataRoot.resolve("render-commands.log"),
                        command.tool() + " " + String.join(" ", command.arguments())
                            + "\nexit=" + result.exitCode() + "\nstdout=" + result.stdout()
                            + "\nstderr=" + result.stderr() + "\n\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return result;
        }
    }
}
