package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.QualityFrameVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 成片抽帧：审片时可回看的画面证据（L-1）。
 *
 * <p>帧以 data URL 直接回给页面，不落库、不当产物——它只回答「这一段屏幕上到底是什么」，
 * 看完即弃。抽帧时间点取自字幕闸里实测的段区间，不另算一套时间轴；
 * 段首通常还留着上一屏的尾巴，所以每段取中点。
 *
 * <p>这是给人看的东西，不进质量报告、不进任何闸：报告里的 {@code screenshot} 仍是
 * 结构化证据（contact_sheet），这里多出来的帧不改写它。
 */
@Service
@RequiredArgsConstructor
public class QualityFrameService {

    /** 六段正文 + 结尾已经够看，再多就该人自己看片了。 */
    private static final int MAX_FRAMES = 8;
    private static final long FFMPEG_TIMEOUT_SECONDS = 20L;
    private static final Pattern SCENE_START = Pattern.compile("scene(\\d+)StartMs");
    private static final String SUBTITLES_GATE = "subtitles";

    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;

    /**
     * 抽一组帧。
     *
     * @param version 已经过权限与归属校验的版本
     * @return 每段一张 + 结尾一张；没有实测区间或没有成片时抛 409，不返回半组
     */
    public List<QualityFrameVo> extract(HeronVideoVersion version) {
        List<Frame> frames = windows(subtitlesMeasured(version.getChecks()));
        if (frames.isEmpty()) {
            throw new HeronBusinessException(409, "quality_frames_unavailable", "没有字幕实测区间，抽不出画面");
        }
        ProductionArtifactVo mp4 = artifactOf(version.getArtifactManifest());
        if (mp4 == null) {
            throw new HeronBusinessException(409, "quality_frames_unavailable", "该版本没有成片");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("heron-quality-frames");
            Path video = materialize(mp4, directory);
            List<QualityFrameVo> out = new ArrayList<>();
            for (Frame frame : frames) {
                out.add(new QualityFrameVo(frame.seq(), frame.label(), frame.atMs(),
                    shot(video, directory, frame)));
            }
            return out;
        } catch (IOException e) {
            throw new HeronBusinessException(503, "quality_frames_failed", "抽帧工作目录不可用");
        } finally {
            deleteQuietly(directory);
        }
    }

    /** 字幕闸的 measured；没有这一闸就返回 null。 */
    private static JsonNode subtitlesMeasured(String checks) {
        if (checks == null || checks.isBlank()) return null;
        JsonNode root = JsonUtils.getJsonMapper().readTree(checks);
        if (root == null || !root.isArray()) return null;
        for (JsonNode check : root) {
            if (SUBTITLES_GATE.equals(check.path("gate").asString(""))) {
                return check.path("measured");
            }
        }
        return null;
    }

    /** 各段与结尾的抽帧点，按段号排序后再截到上限。 */
    private static List<Frame> windows(JsonNode measured) {
        if (measured == null || !measured.isObject()) return List.of();
        TreeMap<Integer, Frame> scenes = new TreeMap<>();
        for (String key : measured.propertyNames()) {
            Matcher matcher = SCENE_START.matcher(key);
            if (!matcher.matches()) continue;
            int sceneNo = Integer.parseInt(matcher.group(1));
            long start = measured.path(key).asLong(0);
            long end = measured.path("scene" + sceneNo + "EndMs").asLong(0);
            if (end <= start) continue;
            scenes.put(sceneNo, new Frame(sceneNo, "第 " + sceneNo + " 段", (start + end) / 2));
        }
        List<Frame> out = new ArrayList<>(scenes.values());
        long closingStart = measured.path("closingStartMs").asLong(0);
        long closingEnd = measured.path("closingEndMs").asLong(0);
        if (closingEnd > closingStart) {
            out.add(new Frame(out.size() + 1, "结尾", (closingStart + closingEnd) / 2));
        }
        return out.size() > MAX_FRAMES
            ? out.subList(0, MAX_FRAMES)
            : out;
    }

    /** 成片产物：清单里 kind=mp4 的那一件。 */
    private static ProductionArtifactVo artifactOf(String manifest) {
        if (manifest == null || manifest.isBlank()) return null;
        JsonNode root = JsonUtils.getJsonMapper().readTree(manifest);
        if (root == null || !root.isArray()) return null;
        for (JsonNode item : root) {
            if (!"mp4".equals(item.path("kind").asString(""))) continue;
            return new ProductionArtifactVo(item.path("artifactId").asString(""),
                item.path("kind").asString(""), item.path("storageKey").asString(""),
                item.path("bytes").asLong(0), item.path("sha256").asString(""),
                item.path("mediaType").asString(""));
        }
        return null;
    }

    /**
     * 拿一份本地成片。
     *
     * <p>本地存储直接用仓库里的文件（先校验哈希）；对象存储模式拷一份到临时目录，
     * 因为 ffmpeg 的快进定位要能随机读，喂流只能从头解码。
     */
    private Path materialize(ProductionArtifactVo mp4, Path directory) throws IOException {
        Path local = artifacts.localPath(mp4);
        if (local != null) {
            artifacts.verify(mp4);
            return local;
        }
        Path copy = directory.resolve("movie.mp4");
        try (InputStream in = artifacts.openVerified(mp4)) {
            Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        return copy;
    }

    /** 抽一张缩略图，回 data URL。 */
    private String shot(Path video, Path directory, Frame frame) throws IOException {
        Path out = directory.resolve("frame-" + frame.seq() + ".jpg");
        Path log = directory.resolve("ffmpeg-" + frame.seq() + ".log");
        List<String> argv = List.of(properties.getFfmpeg(), "-v", "error",
            "-ss", String.format(Locale.ROOT, "%.3f", frame.atMs() / 1000.0),
            "-i", video.toString(), "-frames:v", "1", "-vf", "scale=360:-1", "-q:v", "4",
            "-y", out.toString());
        Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new HeronBusinessException(503, "quality_frames_failed", "ffmpeg 不可用");
        }
        try {
            if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new HeronBusinessException(503, "quality_frames_failed",
                    frame.label() + "抽帧超时");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "quality_frames_failed", "抽帧被中断");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(out)) {
            throw new HeronBusinessException(503, "quality_frames_failed",
                frame.label() + "抽帧失败：" + firstLine(log));
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(out));
    }

    private static String firstLine(Path log) {
        try {
            String text = Files.readString(log);
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (IOException e) {
            return "（ffmpeg 无输出）";
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响本次评审，下次系统自己回收
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 一次抽帧：第几段、画在哪、抽哪一刻。 */
    private record Frame(int seq, String label, long atMs) {
    }
}
