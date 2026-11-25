package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** V3 在生产管线中的实际窗口、额外 ffmpeg 输入、SKIP 序列化与三路混音。 */
@DisplayName("生产管线：V3 声音层集成")
class NewsProductionPipelineSoundIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("缺素材 SKIP 已处理且非阻断，checks 保留代码与未渲染指标")
    void keepsMissingCueAsHandledNonBlockingCheck(@TempDir Path root) throws Exception {
        HeronProductionProperties properties = properties(root);
        ProductionProcessService processes = mock(ProductionProcessService.class);
        NewsProductionPipeline pipeline = pipeline(properties, processes);
        ProductionRunVo run = run();
        Path work = Files.createDirectories(root.resolve("work"));
        var timeline = List.of(new NewsNarrationTiming.Span("scene_001", 0, 3000, false),
            new NewsNarrationTiming.Span("closing-slot", 3000, 4000, false));
        JsonNode subtitles = JSON.readTree("[{\"startMs\":100,\"endMs\":2500,\"text\":\"旁白\"}]");
        JsonNode script = JSON.readTree("{\"scenes\":[{\"on_screen_text\":\"第 1 期\"}]}");

        SoundCueMixer.Result result = pipeline.renderSoundCues(run, work, timeline, subtitles, script);

        org.junit.jupiter.api.Assertions.assertNull(result.bed());
        assertFalse(result.checks().isEmpty());
        String checks = NewsProductionPipeline.gateJson(result.checks().stream()
            .map(NewsProductionPipeline::soundCueGate).toList());
        for (JsonNode check : JSON.readTree(checks)) {
            assertTrue(check.path("passed").asBoolean(), check.toString());
            assertEquals("SKIP", check.path("status").asString());
            assertEquals("MISSING_SFX_ASSET", check.path("code").asString());
            assertTrue(check.path("measured").path("skipped").asBoolean());
            assertFalse(check.path("measured").path("rendered").asBoolean());
        }
        NewsProductionDomain domain = new NewsProductionDomain(null, null, null, null, null, null, null,
            properties, null);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> domain.validateChecks(checks, Set.of("sfx")));
    }

    @Test
    @DisplayName("音效床作为额外 ffmpeg 输入并接入旁白/BGM 总线")
    void addsSoundBedAsExtraMixInput(@TempDir Path root) throws Exception {
        HeronProductionProperties properties = properties(root);
        List<List<String>> commands = new ArrayList<>();
        ProductionProcessService processes = mock(ProductionProcessService.class);
        doAnswer(invocation -> {
            ProductionProcessBo command = invocation.getArgument(1);
            commands.add(List.copyOf(command.arguments()));
            Files.writeString(command.workDirectory().resolve("final.mp4"), "mixed");
            return new ProductionProcessVo(0, false, false, "", "");
        }).when(processes).run(any(), any());
        NewsProductionPipeline pipeline = pipeline(properties, processes);
        Path work = Files.createDirectories(root.resolve("work"));
        Path bgm = work.resolve("bgm.mp3");
        Path bed = work.resolve("sfx-bed.wav");
        Files.writeString(bgm, "bgm");
        Files.writeString(bed, "sfx");
        String envelope = NewsProductionPipeline.bgmEnvelope(
            new NewsNarrationTiming.Span("closing-slot", 3000, 4000, false));

        pipeline.mixTo(run(), work, bgm, envelope, 0, -6, bed);

        List<String> command = commands.getFirst();
        assertEquals(3, command.stream().filter("-i"::equals).count(), command.toString());
        assertTrue(command.contains(bed.toAbsolutePath().toString()));
        String filter = command.get(command.indexOf("-filter_complex") + 1);
        assertTrue(filter.contains("[base][2:a]amix=inputs=2"), filter);
        assertTrue(filter.endsWith("[mix]"));
        assertEquals("copy", command.get(command.indexOf("-c:v") + 1));
        assertFalse(command.contains("[v]"));
    }

    @Test
    @DisplayName("烧字幕时同一次混流改为 libx264 并挂 ASS，参数与 looks 渲染一致")
    void burnsSubtitlesInTheMixCommand(@TempDir Path root) throws Exception {
        HeronProductionProperties properties = properties(root);
        List<List<String>> commands = new ArrayList<>();
        ProductionProcessService processes = mock(ProductionProcessService.class);
        doAnswer(invocation -> {
            ProductionProcessBo command = invocation.getArgument(1);
            commands.add(List.copyOf(command.arguments()));
            Files.writeString(command.workDirectory().resolve("final.mp4"), "burned");
            return new ProductionProcessVo(0, false, false, "", "");
        }).when(processes).run(any(), any());
        NewsProductionPipeline pipeline = pipeline(properties, processes);
        Path work = Files.createDirectories(root.resolve("work"));
        SubtitleBurn.Prepared subtitles = subtitles(root, work);

        pipeline.mixTo(run(), work, null, "volume=1", 0, 0, null, subtitles);

        List<String> command = commands.getFirst();
        String filter = command.get(command.indexOf("-filter_complex") + 1);
        assertTrue(filter.contains("[0:v]ass=filename=composition/subs.ass:fontsdir=composition/fonts[v]"), filter);
        assertEquals("[v]", command.get(command.indexOf("-map") + 1));
        assertEquals("libx264", command.get(command.indexOf("-c:v") + 1));
        assertEquals("medium", command.get(command.indexOf("-preset") + 1));
        assertEquals("16", command.get(command.indexOf("-crf") + 1));
        assertEquals("aac", command.get(command.indexOf("-c:a") + 1));
    }

    @Test
    @DisplayName("烧录编码失败返回字幕闸错误，不冒充 AIGC 元数据失败")
    void reportsSubtitleBurnFailure(@TempDir Path root) throws Exception {
        HeronProductionProperties properties = properties(root);
        ProductionProcessService processes = mock(ProductionProcessService.class);
        org.mockito.Mockito.doReturn(new ProductionProcessVo(1, false, false, "", "ass font missing"))
            .when(processes).run(any(), any());
        NewsProductionPipeline pipeline = pipeline(properties, processes);
        Path work = Files.createDirectories(root.resolve("work"));
        SubtitleBurn.Prepared subtitles = subtitles(root, work);

        org.dromara.heron.domain.HeronBusinessException error = assertThrows(
            org.dromara.heron.domain.HeronBusinessException.class,
            () -> pipeline.mixTo(run(), work, null, "volume=1", 0, 0, null, subtitles));

        assertEquals("news_subtitles_burn_failed", error.getCode());
        assertTrue(error.getMessage().contains("ass font missing"));
    }

    private static SubtitleBurn.Prepared subtitles(Path root, Path work) throws Exception {
        Files.createDirectories(work.resolve("composition"));
        Path template = Files.createDirectories(root.resolve("templates/news"));
        Files.writeString(template.resolve("template.config.json"),
            "{\"key\":\"news\",\"style\":\"article\",\"layout\":\"news\",\"width\":1080,\"height\":1920}");
        Path tokens = Files.createDirectories(root.resolve("templates/_shared/tokens"));
        Files.writeString(tokens.resolve("article.css"), """
            :root { --font-body: "Noto Sans SC", sans-serif; --fs-body: 38px;
                    --fg: #ffffff; --bg: #000000; --pad-frame: 84px; }
            """);
        return SubtitleBurn.prepare(work, template, """
            WEBVTT

            00:00:00.100 --> 00:00:02.000
            字幕烧录验证。
            """);
    }

    @Test
    @Tag("render")
    @DisplayName("合成旁白+真实仓库 BGM/音效三路混音达到交付响度")
    void rendersRealThreeWayMix(@TempDir Path root) throws Exception {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path audioRoot = repository.resolve("data/audio");
        assertTrue(Files.isDirectory(audioRoot.resolve("sfx")), "缺少仓库音效");
        assertTrue(Files.isDirectory(audioRoot.resolve("bgm")), "缺少仓库 BGM");
        Path templateRoot = root.resolve("templates");
        Files.createDirectories(root.resolve("audio/sfx"));
        Files.createDirectories(templateRoot.resolve("audio/bgm"));
        for (String name : List.of("whoosh-short.mp3", "click.mp3", "chime.mp3")) {
            Files.copy(audioRoot.resolve("sfx").resolve(name), root.resolve("audio/sfx").resolve(name));
        }
        Path bgmSource;
        try (var tracks = Files.list(audioRoot.resolve("bgm"))) {
            bgmSource = tracks.filter(Files::isRegularFile).sorted().findFirst().orElseThrow();
        }
        Path bgm = templateRoot.resolve("audio/bgm/music.mp3");
        Files.copy(bgmSource, bgm);
        HeronProductionProperties properties = properties(root);
        properties.setNewsTemplate(templateRoot);
        ProductionArtifactService artifacts = new ProductionArtifactService(properties, null);
        ProductionProcessService processes = new ProductionProcessService(properties, artifacts);
        NewsProductionPipeline pipeline = pipeline(properties, processes);
        ProductionRunVo run = run();
        Path work = artifacts.prepareWorkDirectory(run);
        ProductionProcessVo created = processes.run(run, new ProductionProcessBo("ffmpeg", List.of(
            "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x284:rate=30:duration=4",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4",
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-af", "volume=-7dB",
            "raw.mp4"), work, Duration.ofMinutes(2)));
        assertEquals(0, created.exitCode(), created.stderr());
        var timeline = List.of(new NewsNarrationTiming.Span("scene_001", 0, 1500, false),
            new NewsNarrationTiming.Span("scene_002", 1500, 3000, false),
            new NewsNarrationTiming.Span("closing-slot", 3000, 4000, false));
        JsonNode subtitles = JSON.readTree("[{\"startMs\":100,\"endMs\":2500,\"text\":\"旁白\"}]");
        JsonNode script = JSON.readTree("{\"scenes\":[{\"on_screen_text\":\"第 1 期\"},"
            + "{\"on_screen_text\":\"第二幕\"}]}");
        SoundCueMixer.Result sound = pipeline.renderSoundCues(run, work, timeline, subtitles, script);
        assertTrue(Files.isRegularFile(sound.bed()), "真实音效床必须生成");
        assertEquals(3, sound.checks().stream().filter(SoundCue.Check::passed).count(), sound.checks().toString());
        String envelope = NewsProductionPipeline.bgmEnvelope(timeline.getLast());
        AudioLoudness.Measurement voice = AudioLoudness.measureFile(processes, run, work, "raw.mp4");
        AudioLoudness.Measurement music = AudioLoudness.measureBgm(processes, run, work, bgm, envelope);

        pipeline.mixTo(run, work, bgm, envelope, AudioLoudness.voiceGain(voice),
            AudioLoudness.bgmGain(music), sound.bed());
        AudioLoudness.Measurement finalMix = AudioLoudness.measureFile(processes, run, work, "final.mp4");

        assertTrue(finalMix.deliveryOk(), finalMix.toString());
        System.out.println("V3_THREE_WAY=" + work.resolve("final.mp4").toAbsolutePath());
        System.out.println("V3_THREE_WAY_LOUDNESS=" + finalMix);
    }

    private static NewsProductionPipeline pipeline(HeronProductionProperties properties, ProductionProcessService processes) {
        return new NewsProductionPipeline(null, null, processes,
            new ProductionArtifactService(properties, null), properties, (run, stage, progress) -> true,
            null, null, null, null, null, null, mock(FrozenContextService.class));
    }

    private static HeronProductionProperties properties(Path root) {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(root.resolve("data"));
        properties.setNewsTemplate(root.resolve("templates"));
        properties.setGuardEnabled(false);
        return properties;
    }

    private static ProductionRunVo run() {
        return new ProductionRunVo(8100L, ProductionBizType.news_video, 7L, 4L, 1,
            UUID.randomUUID().toString(), "{}", null);
    }
}
