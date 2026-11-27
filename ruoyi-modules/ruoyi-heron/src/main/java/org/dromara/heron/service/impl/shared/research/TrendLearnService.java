package org.dromara.heron.service.impl.shared.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.service.impl.shared.research.TrendLearningAgent;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendLearnState;
import org.dromara.heron.domain.TrendTechniqueCard;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.dromara.heron.service.impl.shared.media.ReferenceVideoDownloader;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 爆款学习闭环：硬筛、一次模型排序、逐条下载拆解与 ReAct 归纳。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendLearnService {

    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_NOTE_CHARS = 500;
    private static final long LEASE_SECONDS = 30 * 60L;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ATTEMPT = Pattern.compile("attempt=(\\d+)");
    private static final Pattern LEASE = Pattern.compile("lease_until=(\\d+)");
    private static final Pattern RESUME = Pattern.compile("(?:^|;\\s*)resume=([a-z]+)");
    private static final String SELECTION_PROMPT = "/prompt/trend-learn/selection.md";

    private final HeronTrendVideoMapper videos;
    private final OpenAiModelClient model;
    private final ReferenceVideoDownloader downloader;
    private final TrendLearnStorage storage;
    private final TrendVideoDecomposer decomposer;
    private final TrendLearningAgent learningAgent;
    private final TrendLearnDocuments documents;
    private final HeronNewsProperties news;

    /**
     * 跑一轮爆款学习：硬筛候选 → 模型排序 → 逐条领取、下载、拆解、ReAct 归纳 → 写当日汇总。
     *
     * <p>候选按 {@code attempt/lease} 机制单条领取：领取失败（并发已改状态）只计为 skipped；
     * 单条阶段失败只把该视频写回对应失败状态并记入 {@code failures}，不中断整批。
     * 没有候选时也会写一份空汇总，保证当天有可读产出。
     *
     * @return 本轮统计：硬筛候选数、模型选中数、成功数、失败数、领取冲突跳过数、日期、输出目录与失败明细
     */
    public Report run() {
        LocalDate date = documents.today();
        long maxDuration = news.getTrendMaxDurationMs();
        List<HeronTrendVideo> candidates = videos.selectLearnCandidates(maxDuration).stream()
            .filter(video -> eligible(video, maxDuration, Instant.now())).toList();
        if (candidates.isEmpty()) {
            documents.writeSummary(date, new java.util.HashSet<>(videos.selectLearnedAwemeIds()));
            return new Report(0, 0, 0, 0, 0, date, outputDir(date).toString(), List.of());
        }
        int limit = Math.max(1, Math.min(MAX_BATCH_SIZE, news.getTrendLearnBatchSize()));
        List<RankedVideo> selected = select(candidates, limit);
        if (!selected.isEmpty()) {
            learningAgent.requireAvailable();
        }
        int learned = 0;
        int skipped = 0;
        List<Failure> failures = new ArrayList<>();
        for (RankedVideo ranked : selected) {
            HeronTrendVideo video = ranked.video();
            TrendLearnState previous = TrendLearnState.of(video.getLearnState());
            int attempt = attemptOf(video.getLearnNote()) + 1;
            String token = UUID.randomUUID().toString();
            String claimNote = leaseNote(attempt, token,
                "selected; resume=" + resume(previous, video.getOssId(), video.getLearnNote()));
            if (videos.markSelected(video.getId(), previous, video.getLearnNote(), claimNote) != 1) {
                skipped++;
                continue;
            }
            try {
                process(video, ranked, previous, attempt, token, date);
                learned++;
            } catch (StageException e) {
                recordFailure(video, e, attempt);
                failures.add(new Failure(video.getAwemeId(), e.failedState().name(), reason(e.getCause())));
            }
        }
        documents.writeSummary(date, new java.util.HashSet<>(videos.selectLearnedAwemeIds()));
        return new Report(candidates.size(), selected.size(), learned, failures.size(), skipped,
            date, outputDir(date).toString(), List.copyOf(failures));
    }

    private void process(HeronTrendVideo video, RankedVideo ranked, TrendLearnState previous, int attempt,
                         String token, LocalDate date) {
        Path work;
        try {
            work = Files.createTempDirectory("heron-trend-learn-" + video.getId() + "-");
        } catch (IOException e) {
            throw new StageException(TrendLearnState.LEARN_FAILED, TrendLearnState.SELECTED, token, e);
        }
        ReferenceVideoDownloader.Downloaded downloaded = null;
        TrendVideoDecomposer.Decomposition decomposition = null;
        try {
            String resume = resume(previous, video.getOssId(), video.getLearnNote());
            Path source = null;
            Path output = null;
            Map<String, String> rawKeys = Map.of();
            boolean downloadedNow = false;
            if ("download".equals(resume)) {
                try {
                    if (watermarked(video.getDownloadUrl())) {
                        throw new HeronBusinessException(409, "trend_download_watermarked",
                            "下载直链带 watermark=1，已拒绝降级为带水印原片");
                    }
                    downloaded = downloader.download(video.getDownloadUrl(), news.getTrendLearnMaxBytes());
                    TrendLearnStorage.Stored stored = storage.uploadVideo(video, downloaded);
                    int backfilled = videos.backfillOssId(video.getId(), stored.ossId(), token);
                    if (backfilled != 1 && !Long.valueOf(stored.ossId()).equals(video.getOssId())) {
                        throw new HeronBusinessException(409, "trend_learn_state_conflict", "学习原片 OSS 指针无法回填");
                    }
                    video.setOssId(stored.ossId());
                    requireTransition(videos.markDownloaded(video.getId(), stored.ossId(),
                        leaseNote(attempt, token, "downloaded; oss_id=" + stored.ossId()), token));
                    source = downloaded.path();
                    downloadedNow = true;
                } catch (RuntimeException e) {
                    throw new StageException(TrendLearnState.DOWNLOAD_FAILED, TrendLearnState.SELECTED, token, e);
                }
            } else if ("decompose".equals(resume)) {
                try {
                    source = storage.downloadVideo(video, work);
                } catch (RuntimeException e) {
                    throw new StageException(TrendLearnState.DECOMPOSE_FAILED, TrendLearnState.SELECTED, token, e);
                }
            }

            if (!"learn".equals(resume)) {
                TrendLearnState expected = downloadedNow ? TrendLearnState.DOWNLOADED : TrendLearnState.SELECTED;
                try {
                    decomposition = decomposer.decompose(source, work);
                    Map<String, TrendLearnStorage.Stored> raw = storage.uploadRaw(video, decomposition.outputDir());
                    rawKeys = raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().key()));
                    documents.saveRaw(video, decomposition, date);
                    requireTransition(videos.markDecomposed(video.getId(), expected,
                        leaseNote(attempt, token, "decomposed; raw_oss=" + raw.size()), token));
                    output = decomposition.outputDir();
                } catch (RuntimeException e) {
                    throw new StageException(TrendLearnState.DECOMPOSE_FAILED, expected, token, e);
                }
            } else {
                try {
                    output = storage.downloadRaw(video, work);
                    decomposition = new TrendVideoDecomposer.Decomposition(work, output);
                    documents.saveRaw(video, decomposition, date);
                    rawKeys = storage.rawKeys(video);
                } catch (RuntimeException e) {
                    throw new StageException(TrendLearnState.LEARN_FAILED, TrendLearnState.SELECTED, token, e);
                }
            }

            TrendLearnState expected = "learn".equals(resume) ? TrendLearnState.SELECTED : TrendLearnState.DECOMPOSED;
            try {
                TrendTechniqueCard card = learningAgent.learn(video, output.resolve("breakdown.json"),
                    output.resolve("storyboard.jpg"), attempt);
                Path cardPath = documents.saveCard(video, card, ranked.relevance(), ranked.reason(), date,
                    video.getOssId(), rawKeys);
                requireTransition(videos.markLearned(video.getId(), expected,
                    note(attempt, "learned; card=" + cardPath), token));
            } catch (RuntimeException e) {
                throw new StageException(TrendLearnState.LEARN_FAILED, expected, token, e);
            }
        } finally {
            if (decomposition != null) {
                decomposition.close();
            } else {
                deleteTree(work);
            }
            if (downloaded != null) {
                downloaded.close();
            }
        }
    }

    private List<RankedVideo> select(List<HeronTrendVideo> candidates, int limit) {
        String reply = model.chatWithoutUsage(resource(SELECTION_PROMPT), selectionPrompt(candidates, limit));
        try {
            return parseSelection(reply, candidates, limit);
        } catch (IllegalArgumentException e) {
            throw new HeronBusinessException(502, "trend_selection_invalid", "选样模型未返回合法排序: " + e.getMessage(), e);
        }
    }

    public static List<RankedVideo> parseSelection(String reply, List<HeronTrendVideo> candidates, int limit) {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("选样输出为空");
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("选样输出不是 JSON 对象");
        }
        JsonNode root;
        try {
            root = JSON.readTree(reply.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("选样 JSON 无法解析", e);
        }
        JsonNode selected = root.path("selected");
        if (!selected.isArray()) {
            throw new IllegalArgumentException("选样输出缺少 selected 数组");
        }
        Map<String, HeronTrendVideo> pool = new LinkedHashMap<>();
        for (HeronTrendVideo candidate : candidates) {
            pool.put(candidate.getAwemeId(), candidate);
        }
        Map<String, RankedVideo> ranked = new LinkedHashMap<>();
        for (JsonNode item : selected) {
            String awemeId = item.path("awemeId").asString("");
            HeronTrendVideo video = pool.get(awemeId);
            if (video == null) {
                throw new IllegalArgumentException("选样输出包含未知 awemeId: " + awemeId);
            }
            JsonNode relevanceNode = item.path("relevance");
            if (!relevanceNode.isNumber() || !Double.isFinite(relevanceNode.asDouble())
                || relevanceNode.asDouble() < 0 || relevanceNode.asDouble() > 1) {
                throw new IllegalArgumentException("选样相关度必须位于 0 到 1: " + awemeId);
            }
            String reason = item.path("reason").asString("").strip();
            if (reason.isEmpty() || reason.length() > 500) {
                throw new IllegalArgumentException("选样理由缺失或过长: " + awemeId);
            }
            ranked.putIfAbsent(awemeId, new RankedVideo(video, relevanceNode.asDouble(), reason));
        }
        int bounded = Math.min(MAX_BATCH_SIZE, Math.max(0, limit));
        return ranked.values().stream()
            .sorted(Comparator.comparingDouble(RankedVideo::relevance).reversed())
            .limit(bounded).toList();
    }

    public static boolean eligible(HeronTrendVideo video, long maxDurationMs) {
        return eligible(video, maxDurationMs, Instant.now());
    }

    /**
     * 硬筛是否可学：ID 与时长合法、未到终态、互动数大于零。
     * 处于中间态（已选/已下载/已拆解）的还要租约已过期，并且下一步所需输入仍在（直链或 OSS 指针）。
     */
    public static boolean eligible(HeronTrendVideo video, long maxDurationMs, Instant now) {
        if (video == null || video.getAwemeId() == null || video.getAwemeId().isBlank()
            || video.getDurationMs() == null || video.getDurationMs() < 0) {
            return false;
        }
        if (maxDurationMs > 0 && video.getDurationMs() > maxDurationMs) {
            return false;
        }
        TrendLearnState state = TrendLearnState.of(video.getLearnState());
        if (state.terminal()) {
            return false;
        }
        long interactions = value(video.getDiggCount()) + value(video.getCommentCount())
            + value(video.getShareCount()) + value(video.getCollectCount());
        if (interactions <= 0) {
            return false;
        }
        if (state == TrendLearnState.SELECTED || state == TrendLearnState.DOWNLOADED
            || state == TrendLearnState.DECOMPOSED) {
            if (!stale(video.getLearnNote(), now)) {
                return false;
            }
            if (state == TrendLearnState.SELECTED && "download".equals(
                resume(state, video.getOssId(), video.getLearnNote()))) {
                return video.getDownloadUrl() != null && !video.getDownloadUrl().isBlank();
            }
            return video.getOssId() != null;
        }
        if (state == TrendLearnState.SEEN || state == TrendLearnState.DOWNLOAD_FAILED) {
            return video.getDownloadUrl() != null && !video.getDownloadUrl().isBlank();
        }
        return video.getOssId() != null;
    }

    public static int attemptOf(String note) {
        if (note == null) {
            return 0;
        }
        Matcher matcher = ATTEMPT.matcher(note);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String note(int attempt, String detail) {
        String text = "attempt=" + attempt + "; " + detail;
        return text.length() <= MAX_NOTE_CHARS ? text : text.substring(0, MAX_NOTE_CHARS);
    }

    private static String leaseNote(int attempt, String token, String detail) {
        String prefix = "attempt=" + attempt + "; token=" + token + "; ";
        String suffix = "; lease_until=" + Instant.now().plusSeconds(LEASE_SECONDS).toEpochMilli();
        int detailLimit = Math.max(0, MAX_NOTE_CHARS - prefix.length() - suffix.length());
        return prefix + flat(detail, detailLimit) + suffix;
    }

    private void recordFailure(HeronTrendVideo video, StageException error, int attempt) {
        String detail = error.failedState().name().toLowerCase() + ": " + reason(error.getCause());
        int updated = videos.markFailed(video.getId(), error.expected(), error.failedState(),
            note(attempt, detail), error.token());
        if (updated != 1) {
            log.error("学习失败状态写回冲突 videoId={} expected={} cause={}", video.getId(),
                error.expected(), error.getCause().getMessage());
        }
    }

    /** 由当前状态与 note 里的 resume 标记决定重试从 download / decompose / learn 哪一步继续；不可重试状态直接 409。 */
    public static String resume(TrendLearnState state, Long ossId, String note) {
        return switch (state) {
            case SEEN, DOWNLOAD_FAILED -> "download";
            case SELECTED -> {
                Matcher matcher = note == null ? null : RESUME.matcher(note);
                String noted = matcher != null && matcher.find() ? matcher.group(1) : "";
                if ("learn".equals(noted) || "decompose".equals(noted)) {
                    yield noted;
                }
                yield ossId == null ? "download" : "decompose";
            }
            case DOWNLOADED, DECOMPOSE_FAILED -> "decompose";
            case DECOMPOSED, LEARN_FAILED -> "learn";
            default -> throw new HeronBusinessException(409, "trend_learn_not_retryable", "当前学习状态不能重试");
        };
    }

    /** 没有租约信息或租约已过期才算 stale；只有 stale 的中间态允许被其他执行重新领取。 */
    private static boolean stale(String note, Instant now) {
        Matcher matcher = note == null ? null : LEASE.matcher(note);
        if (matcher == null || !matcher.find()) {
            return true;
        }
        try {
            return !now.isBefore(Instant.ofEpochMilli(Long.parseLong(matcher.group(1))));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String selectionPrompt(List<HeronTrendVideo> candidates, int limit) {
        StringBuilder text = new StringBuilder("最多选择 ").append(limit).append(" 条。候选列表：\n");
        for (HeronTrendVideo video : candidates) {
            text.append("- awemeId=").append(video.getAwemeId())
                .append(" rank=").append(video.getRankNo())
                .append(" durationMs=").append(video.getDurationMs())
                .append(" digg=").append(value(video.getDiggCount()))
                .append(" comment=").append(value(video.getCommentCount()))
                .append(" share=").append(value(video.getShareCount()))
                .append(" collect=").append(value(video.getCollectCount()))
                .append(" author=").append(flat(video.getAuthorNickname(), 80))
                .append(" description=").append(flat(video.getDescription(), 300)).append('\n');
        }
        return text.toString();
    }

    private static void requireTransition(int updated) {
        if (updated != 1) {
            throw new HeronBusinessException(409, "trend_learn_state_conflict", "学习状态已被其他执行改变");
        }
    }

    private static boolean watermarked(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
            return java.util.regex.Pattern.compile("(?i)(?:[?&]|%26)watermark=1(?:&|%26|$)")
                .matcher(decoded).find();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String flat(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String reason(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return flat(message, 350);
    }

    private Path outputDir(LocalDate date) {
        return news.getTrendLearnOutputDir().toAbsolutePath().normalize().resolve(date.toString());
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("学习临时目录清理失败 {}", root, e);
        }
    }

    private static String resource(String path) {
        try (InputStream stream = TrendLearnService.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少选样提示词 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("选样提示词不可读 " + path, e);
        }
    }

    /**
     * 一轮学习的汇总。
     *
     * @param candidates 通过硬筛、进入本轮的候选数
     * @param selected   模型排序后实际尝试的条数
     * @param learned    走完学习并写入技法卡的条数
     * @param failed     阶段失败并已写回失败状态的条数
     * @param skipped    领取时状态已被并发改变、本轮未处理的条数
     * @param date       本轮学习对应的日期（产物按此日期分目录）
     * @param outputDir  当日产物目录
     * @param failures   失败明细，与 failed 一一对应
     */
    public record Report(int candidates, int selected, int learned, int failed, int skipped,
                         LocalDate date, String outputDir, List<Failure> failures) { }

    /**
     * 单条视频的学习失败记录。
     *
     * @param awemeId 作品 ID
     * @param state   失败时写入的状态名（如 LEARN_FAILED）
     * @param reason  失败原因（已扁平化并截断）
     */
    public record Failure(String awemeId, String state, String reason) { }

    /** 选样结果：视频、模型给出的相关度（0–1）与选样理由。 */
    public record RankedVideo(HeronTrendVideo video, double relevance, String reason) { }

    private static final class StageException extends RuntimeException {
        private final TrendLearnState failedState;
        private final TrendLearnState expected;
        private final String token;

        private StageException(TrendLearnState failedState, TrendLearnState expected, String token, Throwable cause) {
            super(cause);
            this.failedState = failedState;
            this.expected = expected;
            this.token = token;
        }

        private TrendLearnState failedState() {
            return failedState;
        }

        private TrendLearnState expected() {
            return expected;
        }

        private String token() {
            return token;
        }
    }
}
