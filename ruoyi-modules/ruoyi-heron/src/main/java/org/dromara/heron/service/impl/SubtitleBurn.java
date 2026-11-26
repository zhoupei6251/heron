package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把 edge-tts 的 cue 字幕写成 ASS，并在既有混流命令中烧进画面。 */
final class SubtitleBurn {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern CUE = Pattern.compile(
        "(?m)^(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})[^\\r\\n]*\\r?\\n"
            + "([\\s\\S]*?)(?=\\r?\\n\\s*\\r?\\n|\\z)");
    private static final Pattern TOKEN = Pattern.compile("--([a-z0-9-]+)\\s*:\\s*([^;]+);");
    private static final Pattern PX = Pattern.compile("(-?\\d+(?:\\.\\d+)?)px");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})");
    private static final Pattern DURATION = Pattern.compile("duration=([0-9.]+)");
    private static final int PIXEL_DELTA = 24;
    private static final int MIN_CHANGED_PIXELS = 16;
    private static final long MAX_DURATION_DELTA_MS = 20;

    private SubtitleBurn() { }

    record Style(int width, int height, int fontSize, int marginV, String font,
                 String primaryColour, String outlineColour, String backColour, int marginL) {
        int bandBottom() {
            return height * 2 / 3;
        }
    }

    record Prepared(Path file, Style style, int cues, long sampleAtMs) { }

    record PixelDiff(long inside, long outside) { }

    static Prepared prepare(Path work, Path profileDir, String vtt) {
        return write(work, profileDir, cues(vtt), "subs.ass");
    }

    static Prepared prepareJson(Path work, Path profileDir, JsonNode subtitles, NewsNarrationTiming.Span window) {
        if (subtitles == null || !subtitles.isArray()) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "字幕 cue 清单不是数组");
        }
        List<Cue> cues = new ArrayList<>();
        for (JsonNode cue : subtitles) {
            long start = cue.path("startMs").asLong(-1);
            long end = cue.path("endMs").asLong(-1);
            String text = cue.path("text").asString("");
            if (window != null) {
                if (end <= window.startMs() || start >= window.endMs()) {
                    continue;
                }
                start = Math.max(start, window.startMs()) - window.startMs();
                end = Math.min(end, window.endMs()) - window.startMs();
            }
            if (end > start && start >= 0 && !text.isBlank()) {
                cues.add(new Cue(start, end, text));
            }
        }
        return write(work, profileDir, cues, window == null ? "subs.ass" : "scene.ass");
    }

    private static Prepared write(Path work, Path profileDir, List<Cue> cues, String fileName) {
        if (cues.isEmpty()) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "subs.vtt 没有可烧录的 cue");
        }
        Style style = styleOf(profileDir);
        Path file = work.resolve("composition").resolve(fileName);
        try {
            Files.writeString(file, ass(cues, style));
        } catch (IOException e) {
            throw new HeronBusinessException(503, "news_subtitles_burn_failed", "字幕 ASS 工作文件不可写", e);
        }
        Cue sample = cues.getFirst();
        return new Prepared(file, style, cues.size(), (sample.startMs() + sample.endMs()) / 2);
    }

    static String videoFilter(Prepared prepared) {
        return "ass=filename=composition/" + prepared.file().getFileName() + ":fontsdir=composition/fonts";
    }

    static NewsProductionPipeline.Gate verify(ProductionProcessService processes, ProductionRunVo run, Path work,
                                               String rawFile, String finalFile, Prepared prepared) {
        Path rawFrame = work.resolve("subtitle-raw.png");
        Path finalFrame = work.resolve("subtitle-final.png");
        extract(processes, run, work, rawFile, rawFrame, prepared.sampleAtMs());
        extract(processes, run, work, finalFile, finalFrame, prepared.sampleAtMs());
        try {
            BufferedImage raw = ImageIO.read(rawFrame.toFile());
            BufferedImage burned = ImageIO.read(finalFrame.toFile());
            if (raw == null || burned == null || raw.getWidth() != prepared.style().width()
                || raw.getHeight() != prepared.style().height() || burned.getWidth() != raw.getWidth()
                || burned.getHeight() != raw.getHeight()) {
                throw new HeronBusinessException(409, "news_subtitles_burn_failed", "抽帧尺寸与模板画布不一致");
            }
            PixelDiff diff = compare(raw, burned, prepared.style().bandBottom());
            long durationDeltaMs = Math.abs(durationMillis(processes, run, work, finalFile)
                - durationMillis(processes, run, work, rawFile));
            if (!pixelsPresent(diff) || durationDeltaMs > MAX_DURATION_DELTA_MS) {
                throw new HeronBusinessException(409, "news_subtitles_burn_failed",
                    "字幕像素或时长闸未过：changedPixels=" + diff.inside() + " durationDeltaMs=" + durationDeltaMs);
            }
            Map<String, Object> measured = new TreeMap<>();
            measured.put("cues", prepared.cues());
            measured.put("sampleAtMs", prepared.sampleAtMs());
            measured.put("width", raw.getWidth());
            measured.put("height", raw.getHeight());
            measured.put("bandBottom", prepared.style().bandBottom());
            measured.put("changedPixels", diff.inside());
            measured.put("outsideBandChangedPixels", diff.outside());
            measured.put("durationDeltaMs", durationDeltaMs);
            return new NewsProductionPipeline.Gate("subtitles_burn", "subs.vtt#" + finalFile,
                "ASS 已烧入画面，字幕安全带存在像素且时长偏差不超过二十毫秒", measured);
        } catch (IOException e) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "字幕烧录抽帧不可读", e);
        }
    }

    static boolean pixelsPresent(PixelDiff diff) {
        return diff.inside() >= MIN_CHANGED_PIXELS
            && diff.outside() <= Math.max(64, diff.inside() / 4);
    }

    static PixelDiff compare(BufferedImage raw, BufferedImage burned, int bandBottom) {
        if (raw.getWidth() != burned.getWidth() || raw.getHeight() != burned.getHeight()) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "原片与烧录片抽帧尺寸不一致");
        }
        long inside = 0;
        long outside = 0;
        for (int y = 0; y < raw.getHeight(); y++) {
            for (int x = 0; x < raw.getWidth(); x++) {
                if (delta(raw.getRGB(x, y), burned.getRGB(x, y)) <= PIXEL_DELTA) {
                    continue;
                }
                if (y < bandBottom) {
                    inside++;
                } else {
                    outside++;
                }
            }
        }
        return new PixelDiff(inside, outside);
    }

    private static int delta(int left, int right) {
        int lr = (left >> 16) & 0xFF;
        int lg = (left >> 8) & 0xFF;
        int lb = left & 0xFF;
        int rr = (right >> 16) & 0xFF;
        int rg = (right >> 8) & 0xFF;
        int rb = right & 0xFF;
        return Math.max(Math.abs(lr - rr), Math.max(Math.abs(lg - rg), Math.abs(lb - rb)));
    }

    private static void extract(ProductionProcessService processes, ProductionRunVo run, Path work,
                                String input, Path output, long atMs) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg",
            List.of("-y", "-loglevel", "error", "-ss", fmt(atMs / 1000.0), "-i", input,
                "-frames:v", "1", output.getFileName().toString()), work, TIMEOUT));
        if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "字幕烧录验证抽帧失败");
        }
    }

    private static long durationMillis(ProductionProcessService processes, ProductionRunVo run, Path work, String file) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1", file),
            work, Duration.ofSeconds(30)));
        Matcher matcher = DURATION.matcher(result.stdout());
        if (result.exitCode() != 0 || !matcher.find()) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "烧录前后时长不可测");
        }
        return Math.round(Double.parseDouble(matcher.group(1)) * 1000);
    }

    private static Style styleOf(Path profileDir) {
        try {
            JsonNode config = JSON.readTree(Files.readString(profileDir.resolve("profile.json")));
            int width = config.path("canvas").path("width").asInt(0);
            int height = config.path("canvas").path("height").asInt(0);
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
                throw new HeronBusinessException(409, "news_subtitles_burn_failed", "Profile 画布声明不合法");
            }
            Map<String, String> tokens = tokens(Files.readString(profileDir.resolve("tokens.css")));
            int tokenSize = px(tokens, "--fs-body");
            int referenceWidth = width >= height ? 1920 : 1080;
            int fontSize = Math.max(1, Math.round(tokenSize * width / (float) referenceWidth));
            int marginV = height / 3 + fontSize;
            String font = tokens.get("--font-body").replaceFirst("^\\s*\"([^\"]+)\".*", "$1");
            return new Style(width, height, fontSize, marginV, font,
                assColour(tokens.get("--fg"), "00"), assColour(tokens.get("--bg"), "00"),
                assColour(tokens.get("--bg"), "80"), px(tokens, "--pad-frame"));
        } catch (IOException | RuntimeException e) {
            if (e instanceof HeronBusinessException business) {
                throw business;
            }
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "Profile 字幕令牌不可读", e);
        }
    }

    private static Map<String, String> tokens(String css) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(css);
        while (matcher.find()) {
            values.put("--" + matcher.group(1), matcher.group(2).trim());
        }
        for (String required : List.of("--font-body", "--fs-body", "--fg", "--bg", "--pad-frame")) {
            if (!values.containsKey(required)) {
                throw new HeronBusinessException(409, "news_subtitles_burn_failed", "模板缺少字幕令牌 " + required);
            }
        }
        return values;
    }

    private static int px(Map<String, String> tokens, String key) {
        Matcher matcher = PX.matcher(tokens.get(key));
        if (!matcher.find()) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "字幕令牌 " + key + " 不是像素值");
        }
        return Math.round(Float.parseFloat(matcher.group(1)));
    }

    private static String assColour(String hex, String alpha) {
        Matcher matcher = HEX.matcher(hex);
        if (!matcher.find()) {
            throw new HeronBusinessException(409, "news_subtitles_burn_failed", "字幕颜色令牌不是十六进制颜色");
        }
        String value = matcher.group(1);
        return "&H" + alpha + value.substring(4, 6) + value.substring(2, 4) + value.substring(0, 2);
    }

    private static List<Cue> cues(String vtt) {
        List<Cue> cues = new ArrayList<>();
        Matcher matcher = CUE.matcher(vtt);
        while (matcher.find()) {
            long start = millis(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
            long end = millis(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
            String text = matcher.group(9).replaceAll("<[^>]+>", "").trim();
            if (end > start && !text.isBlank()) {
                cues.add(new Cue(start, end, text));
            }
        }
        return cues;
    }

    private static String ass(List<Cue> cues, Style style) {
        StringBuilder text = new StringBuilder("""
            [Script Info]
            ScriptType: v4.00+
            PlayResX: %d
            PlayResY: %d
            WrapStyle: 2
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Heron,%s,%d,%s,%s,%s,%s,0,0,0,0,100,100,0,0,1,2,0,2,%d,%d,%d,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """.formatted(style.width(), style.height(), style.font(), style.fontSize(), style.primaryColour(),
            style.primaryColour(), style.outlineColour(), style.backColour(), style.marginL(), style.marginL(),
            style.marginV()));
        for (Cue cue : cues) {
            text.append("Dialogue: 0,").append(assTime(cue.startMs())).append(',').append(assTime(cue.endMs()))
                .append(",Heron,,0,0,0,,").append(escape(cue.text())).append('\n');
        }
        return text.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
            .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\N");
    }

    private static String assTime(long millis) {
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        long seconds = millis / 1000 % 60;
        long centiseconds = millis % 1000 / 10;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds);
    }

    private static long millis(String hours, String minutes, String seconds, String fraction) {
        return Long.parseLong(hours) * 3_600_000 + Long.parseLong(minutes) * 60_000
            + Long.parseLong(seconds) * 1000 + Long.parseLong(fraction);
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private record Cue(long startMs, long endMs, String text) { }
}
