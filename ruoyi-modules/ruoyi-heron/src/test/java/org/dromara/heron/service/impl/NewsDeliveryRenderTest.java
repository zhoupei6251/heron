package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("render")
@DisplayName("成片交付层真实 ffmpeg：字幕像素、时长、响度与封面")
class NewsDeliveryRenderTest {

    @Test
    @DisplayName("关字幕重复混流逐字节一致；开字幕烧录且封面可交付")
    void burnsAndCoversOneRealVideo(@TempDir Path root) throws Exception {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(root.resolve("data"));
        properties.setGuardEnabled(false);
        ProductionArtifactService artifacts = new ProductionArtifactService(properties, null);
        ProductionProcessService processes = new ProductionProcessService(properties, artifacts);
        NewsProductionPipeline pipeline = new NewsProductionPipeline(null, null, processes, artifacts, properties,
            (run, stage, progress) -> true, null, null, null, null, null, null, null);
        ProductionRunVo run = new ProductionRunVo(9200L, ProductionBizType.news_explainer, 7L, 4L, 1,
            UUID.randomUUID().toString(), "{}");
        Path work = artifacts.prepareWorkDirectory(run);
        Path composition = Files.createDirectories(work.resolve("composition"));
        Path fonts = Files.createDirectories(composition.resolve("fonts"));
        Files.copy(repository.resolve("data/fonts/NotoSansSC-Regular.otf"), fonts.resolve("NotoSansSC-Regular.otf"));
        Path template = Files.createDirectories(root.resolve("templates/news"));
        Files.writeString(template.resolve("template.config.json"),
            "{\"key\":\"news\",\"style\":\"article\",\"layout\":\"news\",\"width\":160,\"height\":284}");
        Path tokens = Files.createDirectories(root.resolve("templates/_shared/tokens"));
        Files.writeString(tokens.resolve("article.css"), """
            :root { --font-body: "Noto Sans SC", sans-serif; --fs-body: 256px;
                    --fg: #ffffff; --bg: #000000; --pad-frame: 12px; }
            """);
        String vtt = """
            WEBVTT

            00:00:00.200 --> 00:00:01.500
            字幕烧录验证。
            """;
        Files.writeString(composition.resolve("subs.vtt"), vtt);
        SubtitleBurn.Prepared subtitles = SubtitleBurn.prepare(work, template, vtt);

        ProductionProcessVo created = processes.run(run, new ProductionProcessBo("ffmpeg", List.of(
            "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x284:rate=30:duration=4",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4",
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-af", "volume=-7dB",
            "raw.mp4"), work, Duration.ofMinutes(2)));
        assertEquals(0, created.exitCode(), created.stderr());
        AudioLoudness.Measurement rawLoudness = AudioLoudness.measureFile(processes, run, work, "raw.mp4");
        double voiceGain = AudioLoudness.voiceGain(rawLoudness);

        pipeline.mixTo(run, work, null, "volume=1", voiceGain, 0, null);
        String firstHash = DigestUtil.sha256Hex(work.resolve("final.mp4").toFile());
        pipeline.mixTo(run, work, null, "volume=1", voiceGain, 0, null);
        assertEquals(firstHash, DigestUtil.sha256Hex(work.resolve("final.mp4").toFile()), "关闭烧录必须逐字节稳定");

        pipeline.mixTo(run, work, null, "volume=1", voiceGain, 0, null, subtitles);
        NewsProductionPipeline.Gate burnGate = SubtitleBurn.verify(processes, run, work, "raw.mp4", "final.mp4", subtitles);
        assertTrue(burnGate.passed());
        assertTrue(((Number) burnGate.measured().get("changedPixels")).longValue() >= 16);
        assertTrue(((Number) burnGate.measured().get("durationDeltaMs")).longValue() < 20);
        assertTrue(AudioLoudness.measureFile(processes, run, work, "final.mp4").deliveryOk());

        NewsCover.Result cover = NewsCover.create(processes, run, work, work.resolve("final.mp4"), template,
            new NewsNarrationTiming.Span("scene_001", 0, 2500, false));
        assertEquals(160, ImageIO.read(cover.cover().toFile()).getWidth());
        assertEquals(284, ImageIO.read(cover.cover().toFile()).getHeight());
        assertEquals(1080, ImageIO.read(cover.thumb().toFile()).getWidth());
        assertEquals(1080, ImageIO.read(cover.thumb().toFile()).getHeight());
        assertTrue(Boolean.TRUE.equals(cover.gate().measured().get("nonBlank")));
        assertTrue(((Number) cover.gate().measured().get("atMs")).longValue() >= 0);
        assertTrue(((Number) cover.gate().measured().get("atMs")).longValue() < 2500);

        for (int index = 0; index < 3; index++) {
            Path frame = work.resolve("review-" + index + ".png");
            ProductionProcessVo shot = processes.run(run, new ProductionProcessBo("ffmpeg",
                List.of("-y", "-loglevel", "error", "-ss", String.format(java.util.Locale.ROOT, "%.3f", 0.3 + index * 0.4),
                    "-i", "final.mp4", "-frames:v", "1", frame.getFileName().toString()), work, Duration.ofSeconds(30)));
            assertEquals(0, shot.exitCode(), shot.stderr());
        }
        System.out.println("DELIVERY_RENDER_WORK=" + work.toAbsolutePath());
        System.out.println("DELIVERY_BURN_GATE=" + NewsProductionPipeline.gateJson(List.of(burnGate)));
        System.out.println("DELIVERY_COVER_GATE=" + NewsProductionPipeline.gateJson(List.of(cover.gate())));
    }
}
