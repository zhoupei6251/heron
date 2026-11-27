package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.media.SubtitleBurn;
import org.dromara.heron.service.impl.shared.production.NewsNarrationTiming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("字幕烧录：模板令牌、安全区与像素验证")
class SubtitleBurnTest {

    private static final String VTT = """
        WEBVTT

        00:00:00.100 --> 00:00:02.000
        字幕烧录验证。
        """;

    @Test
    @DisplayName("字幕烧录与封面两个开关默认都关")
    void deliverySwitchesDefaultOff() {
        HeronNewsProperties properties = new HeronNewsProperties();

        assertTrue(!properties.getSubtitles().isBurn());
        assertTrue(!properties.getCover().isEnabled());
    }

    @Test
    @DisplayName("竖版字幕使用模板字号，并停在下三分之一之上")
    void burnsPortraitSubtitlesAboveLowerThird(@TempDir Path root) throws Exception {
        Path profile = profile(root, 1080, 1920, 38);
        Path work = Files.createDirectories(root.resolve("work"));
        Files.createDirectories(work.resolve("composition"));
        Files.writeString(work.resolve("composition/subs.vtt"), VTT);

        SubtitleBurn.Prepared prepared = SubtitleBurn.prepare(work, profile, VTT);
        String ass = Files.readString(prepared.file());

        assertEquals(1080, prepared.style().width());
        assertEquals(1920, prepared.style().height());
        assertEquals(38, prepared.style().fontSize());
        assertTrue(prepared.style().marginV() >= 1920 / 3 + 38);
        assertTrue(ass.contains("PlayResX: 1080"));
        assertTrue(ass.contains("PlayResY: 1920"));
        assertTrue(ass.contains("Style: Heron,Noto Sans SC,38"));
        assertTrue(ass.contains("0:00:00.10,0:00:02.00"));
        assertTrue(ass.contains("字幕烧录验证。"));
    }

    @Test
    @DisplayName("横版按 1920 参考宽读取横版令牌字号")
    void burnsLandscapeSubtitlesWithLandscapeTokens(@TempDir Path root) throws Exception {
        Path profile = profile(root, 1920, 1080, 48);
        Path work = Files.createDirectories(root.resolve("work"));
        Files.createDirectories(work.resolve("composition"));
        Files.writeString(work.resolve("composition/subs.vtt"), VTT);

        SubtitleBurn.Prepared prepared = SubtitleBurn.prepare(work, profile, VTT);
        String ass = Files.readString(prepared.file());

        assertEquals(48, prepared.style().fontSize());
        assertTrue(prepared.style().marginV() >= 1080 / 3 + 48);
        assertTrue(ass.contains("PlayResX: 1920"));
        assertTrue(ass.contains("PlayResY: 1080"));
    }

    @Test
    @DisplayName("场景返工只烧目标窗内的 cue，并相对场景起点")
    void preparesOnlyTargetSceneCues(@TempDir Path root) throws Exception {
        Path profile = profile(root, 1080, 1920, 38);
        Path work = Files.createDirectories(root.resolve("work"));
        Files.createDirectories(work.resolve("composition"));
        JsonNode subtitles = JsonMapper.builder().build().readTree("""
            [{"startMs":0,"endMs":1000,"text":"前一段"},
             {"startMs":1000,"endMs":2500,"text":"目标段"},
             {"startMs":2500,"endMs":4000,"text":"后一段"}]
            """);

        SubtitleBurn.Prepared prepared = SubtitleBurn.prepareJson(work, profile, subtitles,
            new NewsNarrationTiming.Span("scene_002", 1500, 2500, false));
        String ass = Files.readString(prepared.file());

        assertEquals("scene.ass", prepared.file().getFileName().toString());
        assertEquals(500, prepared.sampleAtMs());
        assertTrue(ass.contains("0:00:00.00,0:00:01.00"));
        assertTrue(ass.contains("目标段"));
        assertTrue(!ass.contains("前一段") && !ass.contains("后一段"));
    }

    @Test
    @DisplayName("没有 cue 的字幕不能生成空 ASS 冒充烧录成功")
    void rejectsEmptySubtitles(@TempDir Path root) throws Exception {
        Path profile = profile(root, 1080, 1920, 38);
        Path work = Files.createDirectories(root.resolve("work"));
        Files.createDirectories(work.resolve("composition"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> SubtitleBurn.prepare(work, profile, "WEBVTT\n"));

        assertEquals("news_subtitles_burn_failed", error.getCode());
        assertTrue(error.getMessage().contains("cue"));
    }

    @Test
    @DisplayName("安全带内字幕像素必须明显多于带外编码噪声")
    void rejectsGlobalEncodingNoiseAsSubtitles() {
        assertTrue(SubtitleBurn.pixelsPresent(new SubtitleBurn.PixelDiff(100, 0)));
        assertTrue(!SubtitleBurn.pixelsPresent(new SubtitleBurn.PixelDiff(16, 1000)));
        assertTrue(!SubtitleBurn.pixelsPresent(new SubtitleBurn.PixelDiff(0, 0)));
    }

    @Test
    @DisplayName("像素差只统计字幕安全带，编码噪声不会冒充字幕")
    void countsChangedPixelsInsideSafeBand() {
        BufferedImage raw = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        BufferedImage burned = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 150; y++) {
            for (int x = 20; x < 180; x++) {
                burned.setRGB(x, y, 0xFFFFFF);
            }
        }

        SubtitleBurn.PixelDiff diff = SubtitleBurn.compare(raw, burned, 200);

        assertEquals(24_000, diff.inside());
        assertEquals(0, diff.outside());
        assertEquals(0, SubtitleBurn.compare(raw, raw, 200).inside());
    }

    private static Path profile(Path root, int width, int height, int fontSize) throws Exception {
        Path profile = Files.createDirectories(root.resolve("profiles/article"));
        Files.writeString(profile.resolve("profile.json"), """
            {"profileId":"article","canvas":{"width":%d,"height":%d}}
            """.formatted(width, height));
        Files.writeString(profile.resolve("tokens.css"), """
            :root {
              --font-body: "Noto Sans SC", sans-serif;
              --fs-body: %dpx;
              --fg: #f3f0e8;
              --bg: #0b1418;
              --pad-frame: 84px;
            }
            """.formatted(fontSize));
        return profile;
    }
}
