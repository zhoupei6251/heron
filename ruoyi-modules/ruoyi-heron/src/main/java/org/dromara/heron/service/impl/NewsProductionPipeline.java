package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.EscapeUtil;
import org.dromara.heron.agentscope.DirectorRepairAgent;
import org.dromara.heron.agentscope.ShotDirectorAgent;
import org.dromara.heron.agentscope.ShotRenderTools;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.MediaAssetLicensePolicy;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.MediaAssetGateVo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.IMediaAssetService;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.ProductionStageReporter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 稿件只由模型给一次，之后每一步都是确定的外部效果：变量映射、磁盘上改写时间轴、配音、版式闸、渲染、解码、AIGC 元数据。
 * 任何一步失败即抛出，不给半份结果入库的路径；字幕只到 cue 级，因为 edge-tts 的 CLI 给不出词级。
 */
@lombok.extern.slf4j.Slf4j
public class NewsProductionPipeline {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VOICE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(600);
    private static final Duration PACKAGE_TIMEOUT = Duration.ofSeconds(120);
    /** 响度对齐最多试几轮：混一次、测一次、按差值修正后再来。 */
    private static final int MIX_ATTEMPTS = 3;
    private static final String COMPOSITION_DIR = "composition";
    private static final String VOICE_FILE = "narration.mp3";
    private static final String SUBTITLES_FILE = "subs.vtt";
    private static final String VARS_FILE = "vars.json";
    private static final String RAW_FILE = "raw.mp4";
    private static final String FINAL_FILE = "final.mp4";
    private static final String SHEET_FILE = "contact.png";
    private static final String BURNED_FINAL_FILE = "final-burned.mp4";
    /** 模板共用一份字体，落在模板根目录同级的 fonts/ 下，复制模板时注入 composition。 */
    private static final String FONT_NAME = "NotoSansSC-Regular.otf";
    private static final String AIGC_TAG = "AIGC";
    private static final String AIGC_STATEMENT = "内容由 AI 生成";
    private static final int MAX_SCENE_SLOTS = 6;
    private static final int SHEET_TILES = 6;
    private static final int MIN_AUDIO_SECONDS = 30;
    private static final Pattern CUE = Pattern.compile(
        "(?m)^(\\d+):(\\d+):(\\d+)[.,](\\d+) --> (\\d+):(\\d+):(\\d+)[.,](\\d+)[^\\r\\n]*\\r?\\n"
            + "([\\s\\S]*?)(?=\\r?\\n\\s*\\r?\\n|\\z)");
    private static final Pattern STAMP = Pattern.compile(
        "(\\d+):(\\d+):(\\d+)[.,](\\d+) --> (\\d+):(\\d+):(\\d+)[.,](\\d+)");

    private final VideoLanePipelines lanes;
    private final ProductionProcessService processes;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final HeronNewsProperties newsProperties;
    private final ProductionStageReporter stages;
    private final DatasetRouter router;
    private final DatasetFetchService datasets;
    private final DatasetScriptAuthor datasetAuthor;
    private final EditorialReviewer reviewer;
    private final QualityReviewer qualityReviewer;
    private final DirectorRepairAgent repairAgent;
    private final ShotDirectorAgent shotDirector;
    private final NewsSourceFetcher fetcher;
    private final FrozenContextService contexts;
    private final IMediaAssetService mediaAssets;
    private final HttpClient assetHttp;

    public NewsProductionPipeline(NewsScriptAuthor author, KnowledgeScriptAuthor knowledgeAuthor,
                                  ProductionProcessService processes,
                                  ProductionArtifactService artifacts, HeronProductionProperties properties,
                                  ProductionStageReporter stages, DatasetRouter router,
                                  DatasetFetchService datasets, DatasetScriptAuthor datasetAuthor,
                                  EditorialReviewer reviewer, QualityReviewer qualityReviewer,
                                  NewsSourceFetcher fetcher, FrozenContextService contexts) {
        this(author, knowledgeAuthor, processes, artifacts, properties, stages, router, datasets, datasetAuthor,
            reviewer, qualityReviewer, fetcher, contexts, null);
    }

    public NewsProductionPipeline(NewsScriptAuthor author, KnowledgeScriptAuthor knowledgeAuthor,
                                  ProductionProcessService processes,
                                  ProductionArtifactService artifacts, HeronProductionProperties properties,
                                  ProductionStageReporter stages, DatasetRouter router,
                                  DatasetFetchService datasets, DatasetScriptAuthor datasetAuthor,
                                  EditorialReviewer reviewer, QualityReviewer qualityReviewer,
                                  NewsSourceFetcher fetcher, FrozenContextService contexts,
                                  IMediaAssetService mediaAssets) {
        this(author, knowledgeAuthor, processes, artifacts, properties, stages, router, datasets, datasetAuthor,
            reviewer, qualityReviewer, fetcher, contexts, mediaAssets, new HeronNewsProperties());
    }

    public NewsProductionPipeline(NewsScriptAuthor author, KnowledgeScriptAuthor knowledgeAuthor,
                                  ProductionProcessService processes,
                                  ProductionArtifactService artifacts, HeronProductionProperties properties,
                                  ProductionStageReporter stages, DatasetRouter router,
                                  DatasetFetchService datasets, DatasetScriptAuthor datasetAuthor,
                                  EditorialReviewer reviewer, QualityReviewer qualityReviewer,
                                  NewsSourceFetcher fetcher, FrozenContextService contexts,
                                  IMediaAssetService mediaAssets, HeronNewsProperties newsProperties) {
        this(author, knowledgeAuthor, processes, artifacts, properties, stages, router, datasets, datasetAuthor,
            reviewer, qualityReviewer, fetcher, contexts, mediaAssets, newsProperties, null, null);
    }

    public NewsProductionPipeline(NewsScriptAuthor author, KnowledgeScriptAuthor knowledgeAuthor,
                                  ProductionProcessService processes,
                                  ProductionArtifactService artifacts, HeronProductionProperties properties,
                                  ProductionStageReporter stages, DatasetRouter router,
                                  DatasetFetchService datasets, DatasetScriptAuthor datasetAuthor,
                                  EditorialReviewer reviewer, QualityReviewer qualityReviewer,
                                  NewsSourceFetcher fetcher, FrozenContextService contexts,
                                  IMediaAssetService mediaAssets, HeronNewsProperties newsProperties,
                                  DirectorRepairAgent repairAgent, ShotDirectorAgent shotDirector) {
        this.lanes = new VideoLanePipelines(this, author, knowledgeAuthor, datasetAuthor, properties);
        this.processes = processes;
        this.artifacts = artifacts;
        this.properties = properties;
        this.newsProperties = newsProperties;
        this.stages = stages;
        this.router = router;
        this.datasets = datasets;
        this.datasetAuthor = datasetAuthor;
        this.reviewer = reviewer;
        this.qualityReviewer = qualityReviewer;
        this.repairAgent = repairAgent;
        this.shotDirector = shotDirector;
        this.fetcher = fetcher;
        this.contexts = contexts;
        this.mediaAssets = mediaAssets;
        this.assetHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * 冻结输入的读取口径：有上下文行的任务必须与冻结 hash 吻合，没有的是迁移前的老任务。
     * 校验失败在这里就抛，不会走到任何取数/写稿步骤。
     */
    private JsonNode frozenInput(ProductionRunVo run) {
        contexts.verify(run.taskId(), run.inputJson());
        return JSON.readTree(run.inputJson());
    }

    public IProductionPipeline newsExplainer() {
        return lanes::newsExplainer;
    }

    public IProductionPipeline dataBoard() {
        return lanes::dataBoard;
    }

    public IProductionPipeline trendInsight() {
        return lanes::trendInsight;
    }

    public IProductionPipeline knowledgeExplainer() {
        return lanes::knowledgeExplainer;
    }

    record LaneInput(JsonNode snapshot, ProfileRegistry.Profile profile, List<Gate> gates) { }

    record DataInput(DatasetFetchService.Result fetched, JsonNode dataset, boolean digest, EditorialStory story) { }

    LaneInput begin(ProductionRunVo run, ProductionBizType expected) {
        JsonNode input = frozenInput(run);
        if (run.bizType() != expected || !expected.kind().equals(input.path("kind").asString(""))) {
            throw new HeronBusinessException(409, "news_line_conflict", "执行器与冻结产线不一致：" + expected);
        }
        JsonNode locked = input.path("assets");
        ProfileRegistry.Profile profile = locked.has("profileId")
            ? new ProfileRegistry(properties).load(expected, locked.path("profileId").asString(""), locked.path("voiceKey").asString(""))
            : null;
        if (profile != null && (!profile.fingerprint().equals(locked.path("profileSha256").asString(""))
            || profile.version() != locked.path("profileVersion").asInt(0))) {
            throw new HeronBusinessException(409, "news_assets_drifted", "冻结的视觉 Profile 已变化：" + profile.id());
        }
        stages.beginStage(run, "script", 5);
        return new LaneInput(input, profile, new ArrayList<>());
    }

    DataInput fetchData(ProductionRunVo run, LaneInput input) {
        var snapshot = input.snapshot();
        DatasetRouter.Route route = frozenRoute(run, snapshot);
        DatasetWindowContract.Window window = DatasetWindowContract.windowOf(snapshot);
        List<String> incomplete = DatasetWindowContract.windowViolations(window);
        if (!incomplete.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_window_mismatch",
                "冻结的取数口径不完整，不去取数：" + String.join("；", incomplete));
        }
        var fetched = datasets.fetch(run, route.sourceKey(), DatasetWindowContract.pointsOf(snapshot));
        JsonNode dataset = JSON.readTree(fetched.json());
        input.gates().add(dataGate(dataset, window));
        input.gates().add(datasetEvidenceGate(dataset));
        EditorialStory story = storyOf(snapshot);
        if (story != null) {
            input.gates().add(storyGate(story, dataset));
        }
        return new DataInput(fetched, dataset, route.digest(), story);
    }

    static JsonNode validateDraft(LaneInput lane, ReviewOutcome review, DataInput evidence) {
        JsonNode script = JSON.readTree(review.script);
        lane.gates().add(contractGate(script, evidence == null ? null : evidence.dataset(), evidence != null));
        if (evidence == null) {
            lane.gates().add(evidenceGate(script));
        }
        return script;
    }

    ProductionResultVo deliver(ProductionRunVo run, LaneInput lane, ReviewOutcome review, JsonNode script,
                               DataInput evidence, JsonNode trendDoc, String citations) {
        JsonNode input = lane.snapshot();
        JsonNode locked = input.path("assets");
        var profile = lane.profile();
        var gates = lane.gates();
        JsonNode dataset = evidence == null ? null : evidence.dataset();
        var fetched = evidence == null ? null : evidence.fetched();
        boolean datasetLine = evidence != null;
        Path work = artifacts.prepareWorkDirectory(run);
        Path templateDir = profile == null ? templateDir(locked.path("templateKey").asString("")) : null;
        Path composition = profile == null ? copyTemplate(work, templateDir) : DirectorPipeline.prepare(work, profile);
        // 占位稿入库供 data 闸复核；TTS 与模板用解析后的可读文本。
        JsonNode renderScript = datasetLine ? DatasetPlaceholders.resolve(script, dataset, trendDoc) : script;
        // 解说线的图片场景由 V5 selector 选定许可素材，下载到工作目录后交给模板。
        List<MediaAssetSelectionVo> mediaSelections = new ArrayList<>();
        Map<String, String> images = datasetLine ? Map.of()
            : materializeImages(composition, renderScript, input, mediaSelections);
        gates.addAll(mediaSelections.stream().map(NewsProductionPipeline::mediaAssetGate).toList());
        if (profile == null) {
            Set<String> declared = declaredVariables(templateDir);
            String variables = datasetLine
                ? dataTemplateVariables(script, dataset, trendDoc, declared)
                : templateVariables(renderScript, images, declared);
            write(work.resolve(VARS_FILE), variables);
            gates.add(assetsGate(composition, locked, templateDir));
            gates.add(sceneTypeGate(script, templateDir));
            write(composition.resolve("index.html"), bindVariablesString(read(composition.resolve("index.html")), variables));
        } else {
            gates.add(new Gate("assets", "assets#profileSha256", "Profile 与共用渲染资产均与冻结摘要一致",
                Map.of("profileVersion", profile.version(), "profileMatched", true)));
        }

        Path voice = composition.resolve(VOICE_FILE);
        stages.beginStage(run, "voice", 25);
        speak(run, work, renderScript, input.path("assets").path("voiceKey").asString(""));
        double audioSeconds = probeDuration(run, voice);
        gates.add(voiceGate(voice, audioSeconds));
        String vtt = read(composition.resolve(SUBTITLES_FILE));
        String subtitles = subtitleJson(vtt);
        List<NewsNarrationTiming.Span> timeline = NewsNarrationTiming.align(renderScript, JSON.readTree(subtitles),
            Math.round(audioSeconds * 1000));
        gates.add(subtitleGate(composition.resolve(SUBTITLES_FILE), audioSeconds, timeline));
        SubtitleBurn.Prepared burnedSubtitles = newsProperties.getSubtitles().isBurn()
            ? SubtitleBurn.prepare(work, profile == null ? profileDir(input) : composition, vtt) : null;

        stages.beginStage(run, "render", 45);
        JsonNode subtitleNodes = JSON.readTree(subtitles);
        SoundCueMixer.Result sound = renderSoundCues(run, work, timeline, subtitleNodes, renderScript);
        gates.addAll(sound.checks().stream().map(NewsProductionPipeline::soundCueGate).toList());
        List<DirectorPipeline.Shot> shots = profile == null ? List.of()
            : DirectorPipeline.storyboard(renderScript, dataset, images, timeline, profile);
        List<DirectorVisualRework.Sample> samples = shots.isEmpty() ? List.of() : DirectorVisualRework.samples(shots, SHEET_TILES);
        if (profile == null) {
            timeComposition(composition, timeline, audioSeconds);
        } else {
            DirectorPipeline.compose(composition, profile, renderScript, dataset, shots);
            if (shotDirector.enabled()) {
                composeAgentShots(run, composition, profile, renderScript, dataset, shots);
            }
        }
        JsonNode check = checkComposition(run, work, COMPOSITION_DIR);
        long renderStartedAt = System.nanoTime();
        render(run, work, profile == null, COMPOSITION_DIR, RAW_FILE);
        long renderMs = (System.nanoTime() - renderStartedAt) / 1_000_000L;
        gates.add(renderGate(work.resolve(RAW_FILE), renderMs, audioSeconds));
        int[] canvas = profile == null ? templateSize(templateDir) : new int[]{profile.width(), profile.height()};
        gates.add(decodeGate(decode(run, work.resolve(RAW_FILE)), audioSeconds, canvas[0], canvas[1]));

        stages.beginStage(run, "finalize", 85);
        AudioMix audio = mixAndTag(run, work, timeline.get(timeline.size() - 1), sound.bed(), renderScript,
            input, burnedSubtitles);
        gates.add(audio.gate());
        Path finalFile = audio.finalFile();
        if (burnedSubtitles != null) {
            gates.add(SubtitleBurn.verify(processes, run, work, RAW_FILE, FINAL_FILE, burnedSubtitles));
        }
        gates.add(aigcMetadataGate(run, finalFile));
        Path sheet = contactSheet(run, work, finalFile, work.resolve(SHEET_FILE), samples);
        gates.add(visualGate(check, sheet));
        gates.add(videoSignalGate(run, work, finalFile));
        NewsCover.Result cover = newsProperties.getCover().isEnabled()
            ? NewsCover.create(processes, run, work, finalFile, canvas[0], canvas[1], timeline.get(0)) : null;
        if (cover != null) {
            gates.add(cover.gate());
        }
        gates.add(aigcVisibleGate(check, composition));
        gates.sort((left, right) -> left.gate().compareTo(right.gate()));

        String checks = gateJson(gates);
        String qualityReport = qualityReport(run, script, dataset, checks, subtitles, sheet, profile);
        ObjectNode visualRework = null;
        if (profile != null && qualityReport != null) {
            ObjectNode originalReport = (ObjectNode) JSON.readTree(qualityReport);
            JsonNode before = originalReport.path("screenshot");
            if ("needs_review".equals(before.path("verdict").asString(""))) {
                visualRework = JSON.createObjectNode();
                visualRework.set("before", before.deepCopy());
                var candidates = DirectorVisualRework.candidates(originalReport, samples);
                if (candidates.isEmpty()) {
                    visualRework.put("status", "manual_only");
                } else {
                    var patch = candidates.getFirst();
                    visualRework.put("planner", "rules");
                    try {
                        if (candidates.size() > 1 && repairAgent != null && repairAgent.enabled()) {
                            var choice = repairAgent.choose(run, JSON.writeValueAsString(candidates), candidates.size());
                            if (Thread.currentThread().isInterrupted()) {
                                throw new HeronBusinessException(409, "visual_rework_interrupted", "视觉返工已中断");
                            }
                            if (choice.isPresent()) patch = candidates.get(choice.getAsInt());
                            visualRework.put("planner", choice.isPresent() ? "agentscope" : "rules_after_agent_failed");
                        }
                        visualRework.set("patch", JSON.valueToTree(patch));
                        var repair = renderRepair(run, work, profile, composition, script, renderScript, dataset, shots,
                            samples, patch, finalFile, burnedSubtitles, audioSeconds, subtitles, gates, timeline.getFirst());
                        visualRework.set("after", repair.report().path("screenshot"));
                        if (DirectorVisualRework.accepted(repair.report())) {
                            visualRework.put("status", "accepted");
                            finalFile = repair.finalFile();
                            sheet = repair.sheet();
                            composition = repair.composition();
                            checks = repair.checks();
                            cover = repair.cover();
                            qualityReport = withRepairNote(repair.report(), "已自动返工并复检通过：" + patch.shotId() + "。 ");
                        } else {
                            visualRework.put("status", "reverted");
                            visualRework.put("reason", "vision_not_passed");
                            qualityReport = withRepairNote(originalReport, "返工未通过视觉复审，保留原片。 ");
                        }
                    } catch (IOException | RuntimeException e) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new HeronBusinessException(409, "visual_rework_interrupted", "视觉返工已中断", e);
                        }
                        visualRework.put("status", "reverted");
                        visualRework.put("reason", e instanceof HeronBusinessException business ? business.getCode() : "visual_rework_failed");
                        qualityReport = withRepairNote(originalReport, "返工未通过技术检查，保留原片。 ");
                        log.warn("视觉返工回退：versionId={} shot={} reason={}", run.versionId(), patch.shotId(),
                            visualRework.path("reason").asString());
                    }
                }
            }
        }
        String manifest = assetManifest(composition, locked, mediaSelections, null);
        if (!samples.isEmpty()) {
            ObjectNode directorManifest = (ObjectNode) JSON.readTree(manifest);
            directorManifest.set("contactSheetSamples", JSON.valueToTree(samples));
            if (visualRework != null) directorManifest.set("visualRework", visualRework);
            manifest = directorManifest.toString();
        }
        List<ProductionArtifactVo> files = new ArrayList<>(List.of(
            artifacts.finalizeFile(run, "mp4", finalFile, "video/mp4"),
            artifacts.finalizeFile(run, "contact_sheet", sheet, "image/png"),
            artifacts.finalizeFile(run, "script", write(work.resolve("script.json"), JSON.writeValueAsString(script)),
                "application/json"),
            artifacts.finalizeFile(run, "subtitles", write(work.resolve("subtitles.json"), subtitles), "application/json"),
            artifacts.finalizeFile(run, "checks", write(work.resolve("checks.json"), checks), "application/json"),
            artifacts.finalizeFile(run, "asset_manifest",
                write(work.resolve("asset-manifest.json"), manifest),
                "application/json")));
        if (cover != null) {
            ProductionArtifactVo coverArtifact = artifacts.finalizeFile(run, "cover", cover.cover(), "image/png");
            files.add(coverArtifact);
            files.add(artifacts.finalizeFile(run, "cover_thumb", cover.thumb(), "image/png"));
        }
        if (fetched != null) {
            files.add(fetched.artifact());
        }
        if (trendDoc != null) {
            files.add(artifacts.finalizeFile(run, "trend",
                write(work.resolve("trend.json"), JSON.writeValueAsString(trendDoc)), "application/json"));
        }
        if (citations != null) {
            files.add(artifacts.finalizeFile(run, "citations", write(work.resolve("citations.json"), citations),
                "application/json"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceVersionId", run.versionId());
        result.put("artifactManifestSha256", DigestUtil.sha256Hex(JSON.writeValueAsString(files)));
        if (cover != null) {
            ProductionArtifactVo coverArtifact = files.stream().filter(file -> "cover".equals(file.kind())).findFirst().orElseThrow();
            result.put("coverUrl", coverUrl(run.bizId(), run.versionId(), coverArtifact.artifactId()));
        }
        if (burnedSubtitles != null) {
            result.put("subtitlesBurned", true);
        }
        result.put("script", script);
        if (!mediaSelections.isEmpty()) {
            result.put("mediaSelections", JSON.readTree(JSON.writeValueAsString(mediaSelections)));
        }
        // 审稿结论必须随结果落盘：自动发布只认这条记录，内存里通过一次不算数
        result.put("editorialReview", JSON.readTree(review.record));
        if (dataset != null) {
            result.put("dataset", dataset);
        }
        // 趋势判定随结果落盘：报告、复盘与前端都读这份，不从旁白里反推
        if (trendDoc != null) {
            result.put("trend", trendDoc);
        }
        result.put("durationMs", (long) Math.round(audioSeconds * 1000));
        result.put("checks", JSON.readTree(checks));
        if (qualityReport != null) {
            result.put("qualityReport", JSON.readTree(qualityReport));
        }
        String contentProfile = ContentProfileWriter.writeJson(review.hookType, script);
        if (contentProfile != null) {
            result.put("contentProfile", JSON.readTree(contentProfile));
        }
        return new ProductionResultVo(files, JSON.writeValueAsString(result));
    }


    /**
     * Agent 镜头创作：每镜一个受控会话，产出独立 HTML 后嵌入主时间轴。
     *
     * <p>Agent 画面只替换通过静态检查的镜头，检查失败或 Agent 不可用时保留确定性画面——
     * Agent 是创作自由度，不是可用性依赖。检查整体失败时全部回退，不在半新半旧的时间轴上渲染。
     */
    void composeAgentShots(ProductionRunVo run, Path composition, ProfileRegistry.Profile profile,
                           JsonNode script, JsonNode dataset, List<DirectorPipeline.Shot> shots) {
        for (DirectorPipeline.Shot shot : shots) {
            Path shotDir = composition.resolve("shots");
            try {
                Files.createDirectories(shotDir);
                var shotBudget = new org.dromara.heron.agentscope.ResearchBudget(6, 40_000,
                    Duration.ofSeconds(60), java.time.Clock.systemDefaultZone(), null);
                var tools = new ShotRenderTools(shotDir, dataset, Map.of(
                    "profileId", profile.id(), "canvas", profile.width() + "x" + profile.height(),
                    "mode", profile.mode(), "line", profile.line()),
                    read(composition.resolve("tokens.css")), DirectorPipeline.shotBrief(script, shot), shot.id(),
                    shotBudget);
                String goal = "镜头 " + shot.id() + "：画面目的「" + shot.title() + "」，时长 "
                    + String.format(Locale.ROOT, "%.2f", (shot.endMs() - shot.startMs()) / 1000.0) + " 秒。"
                    + (shot.image().isEmpty() ? "" : "可用配图 " + shot.image() + "（相对路径引用）。");
                shotDirector.direct(run, tools, goal, shotDir.resolve(shot.id() + ".html"), shotBudget);
            } catch (IOException | RuntimeException e) {
                log.warn("镜头 {} 的 Agent 创作未完成，保留确定性画面: {}", shot.id(), e.toString());
            }
        }
        int written = (int) shots.stream().filter(shot -> Files
            .isRegularFile(composition.resolve("shots").resolve(shot.id() + ".html"))).count();
        try {
            DirectorPipeline.compose(composition, profile, script, dataset, shots, null, written > 0);
            JsonNode check = checkComposition(run, composition.getParent(), "composition");
            if (check.path("ok").asBoolean(false)) {
                log.info("Agent 创作镜头 {}/{} 通过静态检查", written, shots.size());
            } else {
                DirectorPipeline.compose(composition, profile, script, dataset, shots);
                log.info("Agent 画面检查未过，回退确定性画面");
            }
        } catch (RuntimeException e) {
            DirectorPipeline.compose(composition, profile, script, dataset, shots);
            log.warn("Agent 画面静态检查失败，回退确定性画面: {}", e.toString());
        }
    }

    private record ReworkedVideo(Path finalFile, Path sheet, Path composition, String checks, JsonNode report,
                                 NewsCover.Result cover) { }

    private ReworkedVideo renderRepair(ProductionRunVo run, Path work, ProfileRegistry.Profile profile,
                                       Path originalComposition, JsonNode script, JsonNode renderScript, JsonNode dataset,
                                       List<DirectorPipeline.Shot> shots, List<DirectorVisualRework.Sample> samples,
                                       DirectorVisualRework.Patch patch, Path originalFinal, SubtitleBurn.Prepared burn,
                                       double audioSeconds, String subtitles, List<Gate> originalGates,
                                       NewsNarrationTiming.Span first) throws IOException {
        Path attempt = Files.createDirectory(work.resolve("visual-rework"));
        Path composition = DirectorPipeline.prepare(attempt, profile);
        Files.copy(originalComposition.resolve(VOICE_FILE), composition.resolve(VOICE_FILE));
        Files.copy(originalComposition.resolve(SUBTITLES_FILE), composition.resolve(SUBTITLES_FILE));
        if (burn != null) Files.copy(burn.file(), composition.resolve(burn.file().getFileName()));
        for (String image : shots.stream().map(DirectorPipeline.Shot::image).filter(name -> !name.isBlank()).distinct().toList()) {
            Files.copy(originalComposition.resolve(image), composition.resolve(image));
        }
        DirectorPipeline.compose(composition, profile, renderScript, dataset, shots, patch, false);
        String component = relative(work, composition);
        JsonNode check = checkComposition(run, work, component);
        Path raw = attempt.resolve(RAW_FILE);
        long began = System.nanoTime();
        render(run, work, false, component, relative(work, raw));
        long renderMs = (System.nanoTime() - began) / 1_000_000;
        Path finalFile = attempt.resolve(FINAL_FILE);
        List<Gate> checked = new ArrayList<>(originalGates.stream().filter(gate -> !Set.of("render", "decode", "subtitles_burn",
            "aigc_metadata", "visual", "video_signal", "cover", "aigc_visible").contains(gate.gate())).toList());
        checked.add(renderGate(raw, renderMs, audioSeconds));
        checked.add(decodeGate(decode(run, raw), audioSeconds, profile.width(), profile.height()));
        remuxRepair(run, work, raw, originalFinal, finalFile, burn);
        decodeGate(decode(run, finalFile), audioSeconds, profile.width(), profile.height());
        if (burn != null) {
            checked.add(SubtitleBurn.verify(processes, run, work, relative(work, raw), relative(work, finalFile), burn));
        }
        checked.add(aigcMetadataGate(run, finalFile));
        Path sheet = contactSheet(run, work, finalFile, attempt.resolve(SHEET_FILE), samples);
        checked.add(visualGate(check, sheet));
        checked.add(videoSignalGate(run, work, finalFile));
        checked.add(aigcVisibleGate(check, composition));
        NewsCover.Result cover = newsProperties.getCover().isEnabled()
            ? NewsCover.create(processes, run, work, finalFile, profile.width(), profile.height(), first, attempt) : null;
        if (cover != null) checked.add(cover.gate());
        checked.sort(java.util.Comparator.comparing(Gate::gate));
        String checks = gateJson(checked);
        String report = qualityReport(run, script, dataset, checks, subtitles, sheet, profile);
        return new ReworkedVideo(finalFile, sheet, composition, checks, report == null ? JsonNodeFactory.instance.objectNode()
            : JSON.readTree(report), cover);
    }

    private void remuxRepair(ProductionRunVo run, Path work, Path raw, Path original, Path target, SubtitleBurn.Prepared burn) {
        List<String> args = new ArrayList<>(List.of("-y", "-loglevel", "error", "-i", relative(work, raw), "-i",
            relative(work, original), "-map", "0:v:0", "-map", "1:a:0", "-c:a", "copy"));
        if (burn == null) {
            args.addAll(List.of("-c:v", "copy"));
        } else {
            args.addAll(List.of("-vf", SubtitleBurn.videoFilter(burn), "-c:v", "libx264", "-preset", "medium", "-crf", "16",
                "-pix_fmt", "yuv420p"));
        }
        args.addAll(List.of("-movflags", "+use_metadata_tags", "-metadata", AIGC_TAG + "=" + AIGC_STATEMENT, relative(work, target)));
        var result = processes.run(run, new ProductionProcessBo("ffmpeg", args, work, PACKAGE_TIMEOUT));
        if (result.exitCode() != 0 || size(target) <= 0) {
            throw new HeronBusinessException(503, "visual_rework_mux_failed", "返工画面与原音轨无法合成");
        }
    }

    private static String withRepairNote(JsonNode report, String note) {
        ObjectNode screenshot = (ObjectNode) report.path("screenshot");
        screenshot.put("reason", note + screenshot.path("reason").asString(""));
        return report.toString();
    }

    /** 审稿不阻断草稿交付；已启用视觉审稿时，未通过的版本不走自动确认。 */
    private String qualityReport(ProductionRunVo run, JsonNode script, JsonNode dataset, String checks,
                                 String subtitles, Path sheet, ProfileRegistry.Profile profile) {
        try {
            QualityCriteria.Context ctx = new QualityCriteria.Context(run.versionId(), "final", script, dataset,
                JSON.readTree(checks), subtitles == null ? null : JSON.readTree(subtitles), focusMap(script, dataset));
            return qualityReviewer.review(run, ctx, sheet, SHEET_TILES, profile == null ? "legacy" : profile.id());
        } catch (RuntimeException e) {
            log.warn("质量审稿未完成，成片照常交付（versionId={}）：{}", run.versionId(), e.getMessage());
            return null;
        }
    }

    /**
     * 每段的画面焦点。
     *
     * <p>焦点由同一份稿子推出来，所以它只能证明「引用可解析」，不能证明「画面讲了另一件事」——
     * 这层局限写在审稿报告的 reason 里，不假装能做画面比对。
     */
    private static java.util.Map<Integer, DatasetVisualFocus.Focus> focusMap(JsonNode script, JsonNode dataset) {
        java.util.Map<Integer, DatasetVisualFocus.Focus> focus = new java.util.LinkedHashMap<>();
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray() || dataset == null) {
            return focus;
        }
        for (int index = 0; index < scenes.size(); index++) {
            try {
                focus.put(index + 1, DatasetVisualFocus.forScene(scenes.get(index), dataset));
            } catch (RuntimeException e) {
                // 焦点算不出来就当这一段没有焦点证据，不让审稿整体失败。
                log.warn("第 {} 段的画面焦点无法解析：{}", index + 1, e.getMessage());
            }
        }
        return focus;
    }

    // ---------- 十道闸 ----------

    record Gate(String gate, String inputLocator, String reason, Map<String, Object> measured, boolean passed,
                String status, String code) {
        Gate(String gate, String inputLocator, String reason, Map<String, Object> measured, boolean passed) {
            this(gate, inputLocator, reason, measured, passed, null, null);
        }
        Gate(String gate, String inputLocator, String reason, Map<String, Object> measured) {
            this(gate, inputLocator, reason, measured, true);
        }
    }

    /** 混音定稿的结果：成片位置与响度闸。 */
    /**
     * 一轮「混 → 测 → 修正」的結果。
     *
     * @param ok        是否达到交付口径
     * @param voiceGain 最后一次混音用的旁白增益
     * @param measured  最后一次成片的实测值
     * @param attempts  混了几次
     */
    private record MixAttempt(boolean ok, double voiceGain, AudioLoudness.Measurement measured, int attempts) { }

    private record AudioMix(Path finalFile, Gate gate) { }





    /** 稳定 scene_id 对应的零基下标；不存在或脚本形态畸形返回 -1。 */


    /** 只替换目标屏对象；视觉返工不允许改变口播，否则旧字幕与时间轴会失去对应关系。 */

    /** 前段、目标屏、后段三段视频拼接；原音轨由外层 ffmpeg 直接 map，不参与重混。 */

    /** 目标屏声明过的文字变量必须逐项等于解析后的场景，避免检查了另一屏。 */

    /** 整片时长最多允许一帧量化误差；视频起点、音画偏移、帧数与音轨均必须保持不变。 */

    /**
     * 场景类型必须落在模板声明的能力里。
     *
     * <p>模板声明 supportedSceneTypes 是承诺，不是愿望清单：模型选了模板渲染不了的
     * 类型，画面和稿子就会对不上。这里在渲染前拦住，而不是渲完才发现。
     * 没有 scene_type 的段落不参与判定——那是 v1 稿，本来就只有文字。
     */
    static Gate sceneTypeGate(JsonNode script, Path templateDir) {
        java.util.Set<String> supported = supportedSceneTypes(templateDir);
        if (supported.isEmpty()) {
            // 没声明能力就不做类型约束：空集是「未声明」，不是「什么都不支持」
            Map<String, Object> none = new TreeMap<>();
            none.put("declared", 0);
            return new Gate("scene_type", "template.json",
                "模板未声明场景能力，不做类型约束", none);
        }
        List<String> unsupported = new ArrayList<>();
        for (JsonNode scene : script.path("scenes")) {
            String type = scene.path("scene_type").asString("");
            if (!type.isBlank() && !supported.contains(type)) {
                unsupported.add(type);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new HeronBusinessException(409, "scene_type_unsupported",
                "模板 " + templateDir.getFileName() + " 不支持场景类型：" + String.join("、", unsupported)
                    + "（声明支持：" + String.join("、", supported) + "）");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("declared", supported.size());
        return new Gate("scene_type", "template.json",
            "场景类型必须落在模板声明的能力里", measured);
    }

    /**
     * 画面信号闸：黑屏与冻结帧。
     *
     * <p>黑场拦、冻结只记：整段纯黑一秒以上，画面上没有任何内容可讲，成片本身就不成立，
     * 这样的片不该走到审稿人面前（渲染已经跑完，拦下来不多花渲染的钱，只省下人工）。
     * 冻结帧不一样——静帧有时是刻意的表达，而且阈值需要先收一段量，暂不拦，只把数字记进 checks。
     */
    private Gate videoSignalGate(ProductionRunVo run, Path work, Path finalFile) {
        double seconds = probeDuration(run, finalFile);
        VideoSignalCheck.Result signal = VideoSignalCheck.run(processes, run, work, relative(work, finalFile), seconds);
        Map<String, Object> measured = new TreeMap<>();
        measured.put("blackSpans", signal.black().size());
        measured.put("longestBlackSeconds", round2(signal.longestBlack()));
        measured.put("freezeSpans", signal.freeze().size());
        measured.put("longestFreezeSeconds", round2(signal.longestFreeze()));
        measured.put("videoSeconds", round2(seconds));
        VideoSignalCheck.Span black = VideoSignalCheck.blockingBlack(signal);
        if (black != null) {
            throw new HeronBusinessException(409, "news_video_black_frame",
                "画面信号闸：成片第 " + round2(black.start()) + "–" + round2(black.end())
                    + " 秒整段纯黑（" + round2(black.seconds()) + " 秒），画面上没有内容可讲");
        }
        String reason = signal.freeze().isEmpty()
            ? "全片没有黑场与冻结段"
            : "无黑场；检出冻结 " + signal.freeze().size() + " 段（最长 "
                + round2(signal.longestFreeze()) + " 秒），冻结只记不拦";
        return new Gate("video_signal", FINAL_FILE + ":vf", reason, measured);
    }


    /** 模板没写 supportedSceneTypes 时返回空集，表示不做类型约束（老模板）。 */    @SuppressWarnings("unchecked")
    static java.util.Set<String> supportedSceneTypes(Path templateDir) {
        Path meta = templateDir.resolve("template.json");
        if (!java.nio.file.Files.isRegularFile(meta)) {
            return java.util.Set.of();
        }
        try {
            JsonNode node = JSON.readTree(java.nio.file.Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode types = node.path("supportedSceneTypes");
            if (!types.isArray() || types.isEmpty()) {
                return java.util.Set.of();
            }
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            types.forEach(item -> out.add(item.asString("")));
            return out;
        } catch (RuntimeException | java.io.IOException e) {
            // 读不到能力声明不阻断：模板本身仍由 LOCK 与版式闸判定
            return java.util.Set.of();
        }
    }

    private static Gate contractGate(JsonNode script, JsonNode dataset, boolean dataBoard) {
        List<String> violations = dataBoard
            ? DatasetScriptContract.violations(script, dataset)
            : NewsScriptContract.violations(script);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：" + String.join("；", violations));
        }
        return new Gate("schema", "script", "字段、时长合计与重复检查全过", Map.of("violations", 0));
    }

    /**
     * 数据闸：快照内部一致（facts、哈希、占位符已由取数链重算过），
     * 并且取数口径与冻结输入对得上。
     *
     * <p>口径不一致直接拒：那是「按另一条口径取的数据」，静默出片等于骗人。
     * 口径一致但上游数据变了（活的源每天都在变）不在这里拒——
     * 拒了每日看板线就出不了片——但两个哈希都要留在报告里，不许悄悄换一批数据。
     */
    /**
     * 趋势闸：判定契约、方向词、推测词、证据锚点、占位符可解析全过。
     * 机器判定是唯一对外结论，旁白违背它就退稿；模型异议只进质量报告的推测区，不上画面。
     */
    static Gate trendGate(JsonNode trend, JsonNode script, JsonNode dataset) {
        if (trend == null || !trend.isObject()) {
            throw new HeronBusinessException(409, "trend_contract_missing", "趋势线缺少 trend/1 判定，不能出片");
        }
        List<String> violations = new ArrayList<>(TrendContract.violations(trend, script, dataset));
        // 正文允许引用 {{trend.*}}：解析不到说明稿子引了不存在的键
        try {
            DatasetPlaceholders.resolve(script, dataset, trend);
        } catch (HeronBusinessException e) {
            violations.add(e.getMessage());
        }
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "trend_contract", "趋势稿退回改写：" + String.join("；", violations));
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("points", trend.path("points").asInt(0));
        measured.put("segments", trend.path("segments").size());
        measured.put("evidence", trend.path("evidence").size());
        measured.put("directional", TrendAnalysis.directional(trend));
        measured.put("violations", 0);
        return new Gate("trend", "trend.json#verdict", "方向、推测边界与证据锚点全由机器判定覆盖", measured);
    }

    private static Gate dataGate(JsonNode dataset, DatasetWindowContract.Window window) {
        List<String> problems = DatasetWindowContract.violations(window, dataset);
        if (!problems.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_window_mismatch",
                "取数口径与冻结输入不一致：" + String.join("；", problems));
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("dataSha", true);
        measured.put("rows", dataset.path("rows").size());
        measured.put("points", dataset.path("time_range").path("points").asInt(dataset.path("rows").size()));
        measured.put("windowFrozen", window != null);
        String reason = "快照与 facts、哈希、占位符全部重算通过";
        if (window != null) {
            measured.put("windowPoints", window.points());
            boolean same = DatasetWindowContract.sameBatch(window, dataset);
            measured.put("shaMatched", same);
            if (same) {
                reason = "取数口径与冻结输入一致，快照与 facts、哈希、占位符全部重算通过";
            } else if (window.expectedDataSha256().isBlank()) {
                reason = "取数口径与冻结输入一致，但冻结输入没有期望哈希，只能核口径不能核批次";
            } else {
                reason = "取数口径与冻结输入一致，但上游数据在选题之后更新过：期望 sha "
                    + window.expectedDataSha256() + "，实得 sha " + dataset.path("data_sha256").asString("");
            }
        }
        return new Gate("data", "dataset.json", reason, measured);
    }

    /**
     * 生产期的策划复核：策划结论与生产快照必须是同一件事。
     *
     * <p>只核四个粗粒度不变量——引用还能解析、方向没翻转、覆盖没塌陷、极值没失效。
     * 策划快照与生产快照本来就可能不是同一批数据，所以不做逐点相等；两个哈希都留痕。
     */
    private static Gate storyGate(EditorialStory story, JsonNode dataset) {
        List<String> problems = DatasetStoryContract.evidenceViolations(story, dataset);
        if (!problems.isEmpty()) {
            throw new HeronBusinessException(409, "news_story_evidence_mismatch",
                "策划结论与生产快照对不上：" + String.join("；", problems));
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("refsResolved", true);
        measured.put("refsTotal", story.allEvidenceRefs().size());
        measured.put("directionStable", true);
        measured.put("coverageStable", true);
        String planning = story.basis() == null ? "" : story.basis().planningDataSha256();
        return new Gate("story", "dataset.json#facts",
            "策划结论的引用、方向、覆盖与极值在生产快照里成立（策划哈希 " + planning
                + "，生产哈希 " + dataset.path("data_sha256").asString("") + "）",
            measured);
    }

    /** 冻结输入里的策划结论；没有就是没有，老快照照旧生产。 */
    private static EditorialStory storyOf(JsonNode input) {
        JsonNode node = input.path("selection").path("story");
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        return EditorialStory.fromJson(node);
    }

    /** 审稿发现表达与事实矛盾时定向改一次；改完还不合格就退出，不无限重试。 */
    /**
     * 一次定向修复 + 复审。返回的 record 是最终结论，调用方必须落盘：
     * 自动发布只认这条记录，所以写最终 verdict，不是第一次 FAIL。
     */
    static final class ReviewOutcome {
        String script;
        String record;
        /** 最终稿选中标题的钩子类型；审稿修复换题后必须跟着换，不能沿用旧值。 */
        String hookType;
    }

    ReviewOutcome reviewScript(ProductionRunVo run, String scriptJson, String basisJson,
                                       DatasetFetchService.Result datasetFetched,
                                       String hookType, JsonNode trend, EditorialStory story,
                                       NewsScriptAuthor repairer,
                                       String reviewKind) {
        EditorialReviewer.Verdict verdict = review(run, scriptJson, basisJson, story, reviewKind);
        if (!verdict.blocked()) {
            return reviewed(scriptJson, verdict, 0, reviewKind, hookType);
        }
        // 证据不够：改稿救不了。把核不住的断言删掉只会得到一条没有信息量的稿子，
        // 那不是自愈是降级凑数。这类失败交给槽位层换选题。
        if (verdict.evidenceProblem()) {
            throw reviewBlocked(verdict, "证据不足以支持核心断言");
        }
        // 文案跑在证据前面（夸大、因果、时态）：改一轮再说
        String repaired = scriptJson;
        String repairedHook = hookType;
        if (datasetFetched == null) {
            repaired = repairer.repair(run, scriptJson, verdict.blockingNotes());
        } else {
            // 趋势稿改稿必须带着判定一起改，否则修完方向词又跑回机器结论之外
            DatasetScriptAuthor.Result result = trend == null
                ? datasetAuthor.repair(run, datasetFetched.json(), scriptJson, verdict.blockingNotes(), story)
                : datasetAuthor.repairTrend(run, datasetFetched.json(), JSON.writeValueAsString(trend),
                    scriptJson, verdict.blockingNotes());
            repaired = result.script();
            repairedHook = result.hookType();
        }
        EditorialReviewer.Verdict again = review(run, repaired, basisJson, story, reviewKind);
        if (again.blocked()) {
            throw reviewBlocked(again, "改稿后仍未通过");
        }
        return reviewed(repaired, again, 1, reviewKind, repairedHook);
    }

    private EditorialReviewer.Verdict review(ProductionRunVo run, String scriptJson, String basisJson,
                                             EditorialStory story, String reviewKind) {
        return "evidence-review".equals(reviewKind)
            ? reviewer.reviewEvidence(run, scriptJson, basisJson)
            : reviewer.review(run, scriptJson, basisJson, story);
    }

    private ReviewOutcome reviewed(String script, EditorialReviewer.Verdict verdict,
                                   int attempts, String reviewKind, String hookType) {
        ReviewOutcome outcome = new ReviewOutcome();
        outcome.script = script;
        outcome.hookType = hookType;
        outcome.record = verdict.toRecord(attempts, reviewKind, LocalDateTime.now(ZoneOffset.UTC));
        return outcome;
    }

    /**
     * 审稿没过：用 code 把「换选题能救」和「改稿能救」分开。
     * 槽位层据此决定重试还是放弃，不必再解析自然语言。
     *
     * <p>message 带上 coreFacts 与问题条目：审稿失败不会留下可发布的版本记录，
     * 日志是唯一的现场，看不出是哪一档失败就没法判断该改稿还是换选题。
     */
    private static HeronBusinessException reviewBlocked(EditorialReviewer.Verdict verdict, String what) {
        String code = verdict.unverifiable() ? "editorial_review_unverifiable"
            : verdict.evidenceProblem() ? "editorial_review_unsupported"
            : "editorial_review_blocked";
        String facts = verdict.coreFacts() == null ? "未标注" : verdict.coreFacts();
        return new HeronBusinessException(409, code,
            what + "（coreFacts=" + facts + "）：" + String.join("；", verdict.blockingNotes()));
    }

    /** 解说线审稿依据：只给抓到的证据片段与出处，不给模型自述。 */
    static String evidenceQuotes(JsonNode input) {
        ArrayNode quotes = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : input.path("evidence").path("items")) {
            ObjectNode quote = quotes.addObject();
            quote.put("title", item.path("title").asString(""));
            quote.put("summary", item.path("summary").asString(""));
            quote.put("sourceUrl", item.path("sourceUrl").asString(""));
            quote.put("fetchedAt", item.path("fetchedAt").asString(""));
        }
        return quotes.toString();
    }

    /**
     * 数据源只读冻结值。线内编辑在认领前已经选过并写进 selection，这里再问一次模型
     * 就会出现「快照记的是 A、片子用的是 B」——决策等于没冻结。
     *
     * <p>老快照没有 selection 节点（手工认领或升级前的版本），才退回路由，
     * 保证历史作品仍可重渲。这是兼容分支，不是默认路径。
     */
    private DatasetRouter.Route frozenRoute(ProductionRunVo run, JsonNode input) {
        DatasetRouter.Route frozen = frozenSelection(input);
        if (frozen != null) {
            return frozen;
        }
        log.warn("快照缺少 selection.dataSourceKey，退回路由选择（旧版本兼容）");
        return router.route(run, input);
    }

    /**
     * 纯判定：快照里有 selection 就返回它，没有返回 null 表示要走兼容回退。
     * 单独抽出来是为了能直接断言「生产读的是冻结值」，不必真渲染一遍。
     */
    static DatasetRouter.Route frozenSelection(JsonNode input) {
        JsonNode selection = input.path("selection");
        String sourceKey = selection.path("dataSourceKey").asString("");
        if (sourceKey.isBlank()) {
            return null;
        }
        return new DatasetRouter.Route(sourceKey, selection.path("digest").asBoolean(false));
    }

    private static Gate datasetEvidenceGate(JsonNode dataset) {
        String url = dataset.path("source_url").asString("");
        String at = dataset.path("retrieved_at").asString("");
        String sha = dataset.path("data_sha256").asString("");
        if (!url.matches("https?://\\S{1,2047}") || at.isBlank() || !sha.matches("[0-9a-f]{64}")) {
            throw new HeronBusinessException(409, "dataset_evidence_missing", "快照出处三件不完整，不能出片");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("dataSha", true);
        measured.put("retrievedAt", true);
        measured.put("sourceUrl", true);
        return new Gate("evidence", "dataset.json:source_url", "数据集出处由快照覆盖，不信模型自述", measured);
    }

    private static Gate evidenceGate(JsonNode script) {
        String url = script.path("source_url").asString("");
        String fetchedAt = script.path("fetched_at").asString("");
        String sha = script.path("body_sha256").asString("");
        if (!url.matches("https?://\\S{1,2047}") || fetchedAt.isBlank() || !sha.matches("[0-9a-f]{64}")) {
            throw new HeronBusinessException(409, "news_evidence_missing", "出处三字段不完整，不能出片");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("bodySha", true);
        measured.put("fetchedAt", true);
        measured.put("sourceUrl", true);
        return new Gate("evidence", "script.source_url", "出处由抓取覆盖，不信模型自述", measured);
    }

    /** 知识包按 sourceKey 从 data/knowledge 读取；selection.dataSourceKey 在知识线里存的是它。 */
    KnowledgePack knowledgePack(JsonNode input) {
        return KnowledgePack.load(properties.knowledgeDir(),
            input.path("selection").path("dataSourceKey").asString(""));
    }

    /** 选题时定下的 entryId 列表；写稿只能引用这几条。 */
    static List<String> selecteds(JsonNode input) {
        List<String> ids = new ArrayList<>();
        for (JsonNode id : input.path("selection").path("sourceEvidenceIds")) {
            ids.add(id.asString(""));
        }
        return ids;
    }

    /** 每段引用的 entryId，键是屏号（从 1 起）。 */
    static Map<Integer, List<String>> sceneRefs(JsonNode script) {
        Map<Integer, List<String>> refs = new LinkedHashMap<>();
        JsonNode scenes = script.path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            List<String> ids = new ArrayList<>();
            for (JsonNode id : scenes.get(index).path("entry_ids")) {
                ids.add(id.asString(""));
            }
            refs.put(index + 1, ids);
        }
        return refs;
    }

    /**
     * 引用闸：引用可定位、清单一致、署名齐全、没有照抄原文。
     *
     * <p>四件事缺一不可：前三条是 CC BY 4.0 的许可义务，最后一条是平台原创度与「有修改」的边界。
     * 阈值可配，但只放宽不取消——照抄整段原文永远不能过。
     */
    static Gate citationGate(JsonNode script, String citationsJson, KnowledgePack pack, int maxVerbatim) {
        List<String> violations = new ArrayList<>();
        Map<Integer, List<String>> refs = sceneRefs(script);
        long longest = 0;
        for (Map.Entry<Integer, List<String>> scene : refs.entrySet()) {
            List<String> ids = scene.getValue();
            if (ids.isEmpty()) {
                violations.add("第 " + scene.getKey() + " 段没有引用 entryId");
                continue;
            }
            String sceneText = sceneText(script.path("scenes").get(scene.getKey() - 1));
            for (String id : ids) {
                KnowledgePack.Entry entry = pack.entry(id);
                if (entry == null) {
                    violations.add("第 " + scene.getKey() + " 段引用了不存在的条目 " + id);
                    continue;
                }
                long run = longestCommonRun(sceneText, pack.verifiableText(entry));
                if (run > maxVerbatim) {
                    violations.add("第 " + scene.getKey() + " 段与条目 " + id + " 连续相同 " + run
                        + " 字，超过上限 " + maxVerbatim);
                }
                longest = Math.max(longest, run);
            }
        }
        Set<String> cited = new LinkedHashSet<>();
        for (JsonNode item : JSON.readTree(citationsJson).path("entries")) {
            cited.add(item.path("entryId").asString(""));
        }
        Set<String> referenced = new LinkedHashSet<>();
        refs.values().forEach(referenced::addAll);
        if (!cited.equals(referenced)) {
            violations.add("引用清单与脚本引用集合不一致");
        }
        if (!script.path("closing").asString("").contains(pack.attribution())) {
            violations.add("片尾缺少署名原文");
        }
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "citation_invalid", "引用闸退回：" + String.join("；", violations));
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("entries", cited.size());
        measured.put("scenes", refs.size());
        measured.put("longestVerbatim", longest);
        measured.put("attribution", true);
        return new Gate("citation", "script.scenes.entry_ids",
            "每条引用都能在固定版本知识包里定位，署名与照抄上限通过", measured);
    }

    private static String sceneText(JsonNode scene) {
        return scene.path("lead").asString("") + scene.path("body").asString("");
    }

    /** 最长连续相同片段（按字符，去掉空白）：只用来判照抄，不做模糊相似度。 */
    static long longestCommonRun(String left, String right) {
        int[] a = left.replaceAll("\\s+", "").codePoints().toArray();
        int[] b = right.replaceAll("\\s+", "").codePoints().toArray();
        int[] previous = new int[b.length + 1];
        long longest = 0;
        for (int i = 1; i <= a.length; i++) {
            int[] current = new int[b.length + 1];
            for (int j = 1; j <= b.length; j++) {
                if (a[i - 1] == b[j - 1]) {
                    current[j] = previous[j - 1] + 1;
                    longest = Math.max(longest, current[j]);
                }
            }
            previous = current;
        }
        return longest;
    }

    private static Gate voiceGate(Path voice, double audioSeconds) {
        long bytes = size(voice);
        if (bytes <= 0 || audioSeconds <= 0) {
            throw new HeronBusinessException(503, "news_audio_failed", "配音没有产出可用音频，且不启用本地兜底");
        }
        // 声明时长不控制配音长度；低于 30 秒直接退回，靠补静音凑时长会让视频变空。
        if (audioSeconds < MIN_AUDIO_SECONDS) {
            throw new HeronBusinessException(409, "news_audio_short",
                "配音只有 " + Math.round(audioSeconds) + " 秒，低于 " + MIN_AUDIO_SECONDS + " 秒下限");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("audioMs", (long) Math.round(audioSeconds * 1000));
        measured.put("voiceBytes", bytes);
        return new Gate("audio", VOICE_FILE, "edge-tts 退出 0、实测时长为正且不低于 30 秒", measured);
    }

    private static Gate subtitleGate(Path vtt, double audioSeconds, List<NewsNarrationTiming.Span> timeline) {
        String text = read(vtt);
        int cues = 0;
        long covered = 0;
        Matcher matcher = STAMP.matcher(text);
        while (matcher.find()) {
            cues++;
            covered = Math.max(covered,
                millis(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8)));
        }
        return subtitleGate(cues, covered, audioSeconds, timeline);
    }

    private static Gate subtitleGate(JsonNode subtitles, double audioSeconds, List<NewsNarrationTiming.Span> timeline) {
        int cues = subtitles.size();
        long covered = 0;
        for (JsonNode cue : subtitles) {
            covered = Math.max(covered, cue.path("endMs").asLong(0));
        }
        return subtitleGate(cues, covered, audioSeconds, timeline);
    }

    private static Gate subtitleGate(int cues, long covered, double audioSeconds,
                                     List<NewsNarrationTiming.Span> timeline) {
        long audioMs = Math.round(audioSeconds * 1000);
        // 末条 cue 早于音频收尾是 edge-tts 的常态（尾部静音），按覆盖比例判而不是卡绝对差。
        if (cues == 0 || covered * 10 < audioMs * 9) {
            throw new HeronBusinessException(409, "news_subtitles_failed",
                "字幕 cue 缺失或覆盖不足：cues=" + cues + " coveredMs=" + covered + " audioMs=" + audioMs);
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("audioMs", audioMs);
        measured.put("coveredMs", covered);
        measured.put("cues", cues);
        measured.put("alignedScenes", timeline.size() - 1);
        measured.put("interpolatedBoundaries", timeline.stream().filter(NewsNarrationTiming.Span::cueInterpolated).count());
        for (NewsNarrationTiming.Span span : timeline) {
            String key = "closing-slot".equals(span.id()) ? "closing" : span.id().replace("-", "");
            measured.put(key + "StartMs", span.startMs());
            measured.put(key + "EndMs", span.endMs());
        }
        return new Gate("subtitles", SUBTITLES_FILE, "字幕覆盖音频九成以上，口播文本与各分镜及结尾对应", measured);
    }

    private Gate renderGate(Path raw, long renderMs, double videoSeconds) {
        long bytes = size(raw);
        if (bytes <= 0) {
            throw new HeronBusinessException(503, "news_render_failed", "渲染退出 0 但没有成片文件");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("bytes", bytes);
        measured.put("exitCode", 0);
        // 渲染成本：一条片渲多久、每秒画面花多少毫秒。没有这组数就定不了渲染预算。
        measured.put("renderMs", renderMs);
        measured.put("videoSeconds", round2(videoSeconds));
        measured.put("msPerVideoSecond", videoSeconds > 0 ? Math.round(renderMs / videoSeconds) : 0);
        return new Gate("render", RAW_FILE, "hyperframes render 退出 0 且产物非空", measured);
    }

    private static Gate decodeGate(JsonNode probe, double audioSeconds) {
        return decodeGate(probe, audioSeconds, RAW_FILE);
    }

    private static Gate decodeGate(JsonNode probe, double audioSeconds, String locator) {
        return decodeGate(probe, audioSeconds, locator, 1080, 1920);
    }

    private static Gate decodeGate(JsonNode probe, double audioSeconds, int expectedWidth, int expectedHeight) {
        return decodeGate(probe, audioSeconds, RAW_FILE, expectedWidth, expectedHeight);
    }

    private static Gate decodeGate(JsonNode probe, double audioSeconds, String locator,
                                   int expectedWidth, int expectedHeight) {
        int video = 0;
        int audio = 0;
        int width = 0;
        int height = 0;
        double fps = 0;
        for (JsonNode stream : probe.path("streams")) {
            String kind = stream.path("codec_type").asString("");
            if ("video".equals(kind)) {
                video++;
                width = stream.path("width").asInt(0);
                height = stream.path("height").asInt(0);
                fps = rate(stream.path("r_frame_rate").asString(""));
            } else if ("audio".equals(kind)) {
                audio++;
            }
        }
        double duration = probe.path("format").path("duration").asDouble(0);
        List<String> problems = new ArrayList<>();
        if (video != 1 || audio != 1) {
            problems.add("流数不是 1 视频 1 音频");
        }
        if (width != expectedWidth || height != expectedHeight) {
            problems.add("画面尺寸不是 " + expectedWidth + "x" + expectedHeight);
        }
        if (fps <= 0 || fps > 60) {
            problems.add("帧率异常");
        }
        if (Math.abs(duration - audioSeconds) > 2) {
            problems.add("成片与实测音频时长差超过 2 秒");
        }
        if (!problems.isEmpty()) {
            throw new HeronBusinessException(409, "news_decode_failed", String.join("；", problems));
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("audioStreams", audio);
        measured.put("durationMs", (long) Math.round(duration * 1000));
        measured.put("fps", fps);
        measured.put("videoStreams", video);
        return new Gate("decode", locator + ":streams", "容器、尺寸、流数与时长实测通过", measured);
    }

    private JsonNode decode(ProductionRunVo run, Path file) {
        Path work = work(run);
        return decodeFile(run, work, relative(work, file));
    }

    private JsonNode decodeFile(ProductionRunVo run, Path work, String relativeFile) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_format", "-show_streams", "-of", "json", relativeFile), work, PROBE_TIMEOUT));
        if (result.exitCode() != 0) {
            throw new HeronBusinessException(409, "news_probe_failed", "ffprobe 读取成片失败");
        }
        return JSON.readTree(result.stdout().substring(result.stdout().indexOf('{')));
    }
    private Gate aigcMetadataGate(ProductionRunVo run, Path finalFile) {
        Path work = work(run);
        ProductionProcessVo probe = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_entries", "format_tags=" + AIGC_TAG, "-of", "default=nw=1:nk=1",
                relative(work, finalFile)), work, PROBE_TIMEOUT));
        if (probe.exitCode() != 0 || !AIGC_STATEMENT.equals(probe.stdout().trim())) {
            throw new HeronBusinessException(409, "news_aigc_metadata_missing", "成片缺少 AIGC 元数据声明，拒绝进入发布");
        }
        return new Gate("aigc_metadata", FINAL_FILE + ":tags", "ffprobe 读回的 AIGC 文本与声明一致",
            Map.of("aigcTag", true));
    }

    /** 稳定 scene_id 对应的零基下标；不存在或脚本形态畸形返回 -1。 */
    static int sceneIndex(JsonNode script, String sceneId) {
        if (script == null || sceneId == null || !script.path("scenes").isArray()) {
            return -1;
        }
        for (int index = 0; index < script.path("scenes").size(); index++) {
            JsonNode scene = script.path("scenes").get(index);
            if (scene != null && scene.isObject()
                && sceneId.equals(scene.path("scene_id").asString(""))) {
                return index;
            }
        }
        return -1;
    }

    /** 只替换目标屏对象；不允许改变口播，否则旧字幕与时间轴会失去对应关系。 */
    static JsonNode replaceSceneBySceneId(String sceneId, JsonNode previousScript, JsonNode replacement) {
        int index = sceneIndex(previousScript, sceneId);
        if (index < 0) {
            throw new HeronBusinessException(409, "scene_missing", "脚本没有 scene_id=" + sceneId);
        }
        if (replacement == null || !replacement.isObject()
            || !sceneId.equals(replacement.path("scene_id").asString(""))) {
            throw new HeronBusinessException(409, "scene_mismatch", "替换场景必须保留同一个 scene_id");
        }
        ObjectNode updated = (ObjectNode) previousScript.deepCopy();
        ((ArrayNode) updated.path("scenes")).set(index, replacement.deepCopy());
        return updated;
    }

    private static int checkErrors(JsonNode check) {
        int errors = 0;
        for (String section : List.of("lint", "runtime", "layout", "contrast", "craft", "audio")) {
            errors += check.path(section).path("errorCount").asInt(0);
        }
        return errors;
    }

    private static Gate visualGate(JsonNode check, Path sheet) {
        int errors = checkErrors(check);
        int warnings = 0;
        // check --json 的每一段是 {ok, errorCount, warningCount, findings:[…]}，计数只认它自己的计数器。
        for (String section : List.of("lint", "runtime", "layout", "contrast", "craft", "audio")) {
            warnings += check.path(section).path("warningCount").asInt(0);
        }
        int blank = blankCells(sheet);
        if (!check.path("ok").asBoolean(false) || errors > 0 || blank > 0) {
            throw new HeronBusinessException(409, "news_visual_failed", "版式闸未过：errors=" + errors + " blankFrames=" + blank);
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("blankFrames", blank);
        measured.put("checkErrors", errors);
        measured.put("checkWarnings", warnings);
        // inputLocator 与 reason 都禁含路径分隔符（NewsProductionDomain.validateChecks），只能用 # 定位。
        return new Gate("visual", "composition#index.html", "check 无 error 且联络表逐格非空白", measured);
    }

    private static Gate aigcVisibleGate(JsonNode check, Path composition) {
        if (!Files.isRegularFile(composition.resolve("index.motion.json"))) {
            throw new HeronBusinessException(409, "news_aigc_assertion_missing", "模板缺少 AIGC 角标的 motion 断言");
        }
        JsonNode motion = check.path("motion");
        if (!motion.path("ok").asBoolean(false) || motion.path("errorCount").asInt(0) > 0) {
            throw new HeronBusinessException(409, "news_aigc_visible_failed", "AIGC 角标的 motion 断言未过");
        }
        return new Gate("aigc_visible", "#aigc-badge", "角标 appearsBy 与 staysInFrame 断言随 check 一起通过",
            Map.of("badgeAssertion", true, "motionSamples", motion.path("samples").asInt(0)));
    }







    private static Gate assetsGate(Path composition, JsonNode locked, Path templateDir) {
        String manifestSha = sha256(composition.resolve("assets-manifest.json"));
        String fontSha = sha256(composition.resolve("fonts").resolve(FONT_NAME));
        String templateKey = templateDir.getFileName().toString();
        boolean manifestMatched = manifestSha.equals(locked.path("assetManifestSha256").asString(""));
        boolean fontMatched = fontSha.equals(locked.path("fontSha256").asString(""));
        boolean templateMatched = templateKey.equals(locked.path("templateKey").asString(""));
        if (!manifestMatched || !fontMatched || !templateMatched) {
            throw new HeronBusinessException(503, "news_assets_drifted", "模板、字体或素材清单与模板目录的 LOCK.json 声明不一致");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("fontMatched", fontMatched);
        measured.put("manifestMatched", manifestMatched);
        measured.put("templateMatched", templateMatched);
        return new Gate("assets", "LOCK.json", "模板目录实算哈希逐项等于 LOCK.json 声明", measured);
    }

    static String coverUrl(long workId, long versionId, String artifactId) {
        return "/heron/newsWork/" + workId + "/versions/" + versionId + "/preview/" + artifactId;
    }

    static String gateJson(List<Gate> gates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Gate gate : gates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gate", gate.gate());
            item.put("inputLocator", gate.inputLocator());
            item.put("passed", gate.passed());
            item.put("reason", gate.reason());
            item.put("measured", gate.measured());
            if (gate.status() != null) {
                item.put("status", gate.status());
                item.put("code", gate.code());
            }
            list.add(item);
        }
        return JSON.writeValueAsString(list);
    }

    // ---------- 外部命令 ----------

    private Path work(ProductionRunVo run) {
        return artifacts.prepareWorkDirectory(run);
    }

    private void speak(ProductionRunVo run, Path work, JsonNode script, String voiceKey) {
        if (voiceKey.isBlank()) {
            throw new HeronBusinessException(503, "news_assets_unconfigured", "没有锁定配音音色");
        }
        write(work.resolve("narration.txt"), String.join("\n", NewsNarrationTiming.spoken(script)));
        List<String> args = new ArrayList<>(List.of("-m", "edge_tts", "-f", "narration.txt", "-v", voiceKey,
            "--no-progress",
            "--write-media", COMPOSITION_DIR + "/" + VOICE_FILE,
            "--write-subtitles", COMPOSITION_DIR + "/" + SUBTITLES_FILE));
        String rate = properties.getVoiceRate();
        if (rate != null && !rate.isBlank()) {
            args.add(2, "--rate=" + rate);
        }
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("python", args, work, VOICE_TIMEOUT));
        if (result.exitCode() != 0) {
            throw new HeronBusinessException(503, "news_audio_failed", "配音失败：" + safeTail(result.stderr()));
        }
    }

    private JsonNode checkComposition(ProductionRunVo run, Path work, String composition) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("node",
            List.of(cliEntry(), "check", composition, "--json"), work, CHECK_TIMEOUT));
        if (result.exitCode() != 0) {
            throw new HeronBusinessException(409, "news_check_failed",
                "版式闸失败：" + safeHead(result.stdout() + " " + result.stderr()));
        }
        return JSON.readTree(result.stdout().substring(result.stdout().indexOf('{')));
    }

    private void render(ProductionRunVo run, Path work, boolean legacy, String composition, String output) {
        List<String> command = new ArrayList<>(List.of(cliEntry(), "render", composition));
        if (legacy) {
            command.addAll(List.of("--variables-file", VARS_FILE, "--strict-variables"));
        }
        command.addAll(List.of("--fps", "30", "--quality", "looks", "--workers", "1", "--no-best-effort", "--quiet", "--output", output));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("node", command, work, RENDER_TIMEOUT));
        if (result.exitCode() != 0 || size(work.resolve(output)) <= 0) {
            throw new HeronBusinessException(503, "news_render_failed",
                "渲染失败：" + safeTail(result.stdout()) + safeTail(result.stderr()));
        }
    }


    /** 三段视频硬切拼接，原音轨直接复制；AIGC 标签随新容器重新写入。 */


    /** 对压缩音轨做逐包哈希；只有视频重编码时该值才应前后一致。 */




    /** HyperFrames 的 audio clip 必须可读；从旧成片提取临时音轨，最终拼接仍复制旧音轨。 */


    /** V3：只使用实测场景窗与字幕句窗规划，音效床非空时才作为额外混音输入。 */
    SoundCueMixer.Result renderSoundCues(ProductionRunVo run, Path work,
                                                   List<NewsNarrationTiming.Span> timeline,
                                                   JsonNode subtitles, JsonNode script) {
        List<SoundCue.Window> scenes = timeline.stream()
            .map(span -> new SoundCue.Window(span.id(), span.startMs(), span.endMs())).toList();
        List<SoundCue.Window> narration = new ArrayList<>();
        for (int index = 0; index < subtitles.size(); index++) {
            JsonNode cue = subtitles.get(index);
            narration.add(new SoundCue.Window("cue-" + (index + 1), cue.path("startMs").asLong(-1),
                cue.path("endMs").asLong(-1)));
        }
        Path root = properties.getNewsTemplate();
        Path sfx = root == null ? Path.of("data/audio/sfx") : root.toAbsolutePath().normalize().getParent()
            .resolve("audio").resolve("sfx");
        SoundCue.Plan plan = SoundCue.plan(scenes, narration, script, sfx);
        return SoundCueMixer.render(processes, run, work, plan, narration,
            timeline.getLast().endMs());
    }

    static Gate soundCueGate(SoundCue.Check check) {
        Map<String, Object> measured = JSON.convertValue(check.toMap().get("measured"), Map.class);
        boolean skip = "SKIP".equals(check.status());
        return new Gate("sfx", check.toMap().get("inputLocator").toString(), check.reason(), measured,
            check.passed(), skip ? check.status() : null, skip ? check.code() : null);
    }

    /**
     * 混音定稿：先测后调，混完再复测，不达标就修正增益重来。
     *
     * <p>三步走的原因：配乐过去是固定增益直接叠加，而仓库里 6 首 BGM 自身响度差了 8 dB，
     * 最响的那首在片尾实测压过旁白 1.3 dB；{@code amix normalize=0} 的直接叠加也没有任何
     * 检测会拦住峰值越界。现在按目标响度反算增益，混完复测，超标就处理。
     *
     * <p>为什么必须「混完再测」：EBU R128 的整体响度是门控后的均值，配乐填进旁白的停顿之后，
     * 原来被整段排除的静音变成了 −28 LUFS 的底噪，测得的整体响度会比旁白自身低 2 dB 上下。
     * 只按旁白算增益、成片一定不达标。所以增益以成片的实测值为准来修正。
     *
     * <p>失败分两级：旁白自身不达标是内容主体的问题，直接阻塞出片；
     * 配乐导致超标则去掉配乐重混——音乐是加分项，不该为它卡住一条片子。
     */
    private AudioMix mixAndTag(ProductionRunVo run, Path work, NewsNarrationTiming.Span closing, Path sfxBed,
                               JsonNode script, JsonNode input, SubtitleBurn.Prepared subtitles) {
        // 测旁白：量的是渲染后成片里的那条音轨，不是工作目录里的配音文件
        AudioLoudness.Measurement voice = AudioLoudness.measureFile(processes, run, work, RAW_FILE);
        double voiceGain = AudioLoudness.voiceGain(voice);
        BgmCatalog.Pick pick = pickBgm(script, input, run);
        Path bgm = pick.file();
        String envelope = bgmEnvelope(closing);
        AudioLoudness.Measurement bgmMeasured = null;
        double bgmGain = 0;
        if (bgm != null) {
            // 配乐连包络一起测：正文与片尾音量不同，只测原始曲目会算错增益
            bgmMeasured = AudioLoudness.measureBgm(processes, run, work, bgm, envelope);
            bgmGain = AudioLoudness.bgmGain(bgmMeasured);
        }
        MixAttempt attempt = mixAndMeasure(run, work, bgm, envelope, voiceGain, bgmGain, sfxBed, subtitles, MIX_ATTEMPTS);
        boolean bgmDropped = false;
        if (!attempt.ok() && bgm != null) {
            // 配乐导致超标：去掉配乐重来一次，不为音乐卡住出片
            attempt = mixAndMeasure(run, work, null, envelope, voiceGain, 0, sfxBed, subtitles, MIX_ATTEMPTS);
            bgmDropped = true;
        }
        if (!attempt.ok()) {
            throw new HeronBusinessException(409, "news_audio_loudness_failed",
                "成片响度不达标：整体 " + fmt(attempt.measured().integratedLufs()) + " LUFS，真峰值 "
                    + fmt(attempt.measured().truePeakDb()) + " dBTP；目标 " + fmt(AudioLoudness.TARGET_LUFS)
                    + " LUFS ±" + fmt(AudioLoudness.TOLERANCE_LUFS) + "，真峰值上限 "
                    + fmt(AudioLoudness.MAX_TRUE_PEAK) + " dBTP");
        }
        return new AudioMix(work.resolve(FINAL_FILE),
            audioMixGate(voice, attempt, bgm, bgmMeasured, bgmGain, bgmDropped, sfxBed != null, pick));
    }

    /**
     * 混一次、测一次，不达标就按实测差值修正旁白增益再混。
     *
     * <p>增益与实测响度近似线性（限幅器没介入时），所以第二轮一般就收敛；
     * 限幅器介入后关系会被压平，再多试也上不去，那就该按「旁白不达标」处理。
     */
    private MixAttempt mixAndMeasure(ProductionRunVo run, Path work, Path bgm, String envelope,
                                      double voiceGain, double bgmGain, Path sfxBed,
                                      SubtitleBurn.Prepared subtitles, int maxAttempts) {
        double gain = voiceGain;
        AudioLoudness.Measurement measured = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            mixTo(run, work, bgm, envelope, gain, bgmGain, sfxBed, subtitles);
            measured = AudioLoudness.measureFile(processes, run, work, FINAL_FILE);
            if (measured.deliveryOk()) {
                return new MixAttempt(true, gain, measured, attempt);
            }
            double residual = AudioLoudness.TARGET_LUFS - measured.integratedLufs();
            if (Math.abs(residual) < 0.2) {
                // 差得不多但仍在范围外：再混一次也不会变，去留由失败分支处理
                break;
            }
            gain += residual;
        }
        return new MixAttempt(false, gain, measured, maxAttempts);
    }

    /** 混音与 AIGC 元数据一条命令完成；BGM 为空时就是只贴标签。 */
    void mixTo(ProductionRunVo run, Path work, Path bgm, String envelope,
               double voiceGain, double bgmGain, Path sfxBed) {
        mixTo(run, work, bgm, envelope, voiceGain, bgmGain, sfxBed, null);
    }

    void mixTo(ProductionRunVo run, Path work, Path bgm, String envelope,
               double voiceGain, double bgmGain, Path sfxBed, SubtitleBurn.Prepared subtitles) {
        List<String> command = new ArrayList<>();
        command.addAll(List.of("-y", "-loglevel", "error", "-i", RAW_FILE));
        int sfxInput = 1;
        if (bgm != null) {
            command.addAll(List.of("-stream_loop", "-1", "-i", bgm.toAbsolutePath().toString()));
            sfxInput = 2;
        }
        if (sfxBed != null) {
            command.addAll(List.of("-i", sfxBed.toAbsolutePath().toString()));
        }
        String filter = mixFilter(envelope, voiceGain, bgmGain, bgm != null);
        if (sfxBed != null) {
            filter = SoundCueMixer.mixIntoBus(filter, "[" + sfxInput + ":a]");
        }
        if (subtitles != null) {
            filter = "[0:v]" + SubtitleBurn.videoFilter(subtitles) + "[v];" + filter;
        }
        command.addAll(List.of("-filter_complex", filter));
        command.addAll(List.of("-map", subtitles == null ? "0:v:0" : "[v]", "-map", "[mix]"));
        if (subtitles == null) {
            command.addAll(List.of("-c:v", "copy"));
        } else {
            command.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "16", "-pix_fmt", "yuv420p"));
        }
        command.addAll(List.of("-c:a", "aac", "-b:a", "160k"));
        command.addAll(List.of("-movflags", "+use_metadata_tags",
            "-metadata", AIGC_TAG + "=" + AIGC_STATEMENT, FINAL_FILE));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg", command,
            work, PACKAGE_TIMEOUT));
        if (result.exitCode() != 0 || size(work.resolve(FINAL_FILE)) <= 0) {
            // 混音失败不静默降级成无 BGM：退回去等于掩盖一次真实的编码失败
            if (subtitles != null) {
                throw new HeronBusinessException(503, "news_subtitles_burn_failed",
                    "字幕烧录或 AIGC 元数据写入失败：" + safeTail(result.stderr()));
            }
            throw new HeronBusinessException(503, "news_aigc_metadata_failed", "写入 AIGC 元数据失败");
        }
    }

    /**
     * 混音滤镜链。
     *
     * <p>旁白先按实测响度加增益，再限幅；配乐连包络一起加增益再限幅；两路相加后还要再限一次——
     * 两路各自的峰值都不越界，不代表相加后不越界。
     */
    static String mixFilter(String envelope, double voiceGain, double bgmGain, boolean withBgm) {
        String limiter = AudioLoudness.limiter();
        StringBuilder text = new StringBuilder();
        text.append("[0:a]volume=").append(AudioLoudness.fmt(AudioLoudness.factor(voiceGain)))
            .append(',').append(limiter).append("[vo];");
        if (withBgm) {
            text.append("[1:a]").append(envelope)
                .append(",volume=").append(AudioLoudness.fmt(AudioLoudness.factor(bgmGain)))
                .append(',').append(limiter).append("[bg];");
            text.append("[vo][bg]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,")
                .append(limiter).append("[mix]");
        } else {
            text.append("[vo]").append(limiter).append("[mix]");
        }
        return text.toString();
    }

    /**
     * 正文配乐增益0.22，实际结尾内平滑升至0.40，完整片尾淡出；短结尾缩短两段过渡。
     */
    static String bgmEnvelope(NewsNarrationTiming.Span closing) {
        if (closing == null || !"closing-slot".equals(closing.id()) || closing.startMs() < 0
            || closing.endMs() <= closing.startMs()) {
            throw new HeronBusinessException(409, "news_audio_alignment", "配乐缺少有效的实际结尾时间");
        }
        double start = closing.startMs() / 1000.0;
        double total = closing.endMs() / 1000.0;
        double halfClosing = (closing.endMs() - closing.startMs()) / 2000.0;
        double rise = Math.min(0.8, halfClosing);
        double fade = Math.min(1.2, halfClosing);
        return "atrim=duration=" + fmt(total) + ",asetpts=PTS-STARTPTS,"
            + "volume='if(isnan(t),0.22,0.22+0.18*min(1,max(0,(t-" + fmt(start) + ")/" + fmt(rise)
            + ")))':eval=frame,afade=t=in:st=0:d=" + fmt(Math.min(0.8, total / 2)) + ","
            + "afade=t=out:st=" + fmt(total - fade) + ":d=" + fmt(fade);
    }

    /**
     * 响度闸：把两侧的实测值都留痕，让人能复核这条片子为什么是现在这个响度。
     */
    private static Gate audioMixGate(AudioLoudness.Measurement voice, MixAttempt attempt, Path bgm,
                                     AudioLoudness.Measurement bgmMeasured, double bgmGain,
                                     boolean bgmDropped, boolean withSfx, BgmCatalog.Pick pick) {
        Map<String, Object> measured = new TreeMap<>();
        measured.put("voiceLufs", round2(voice.integratedLufs()));
        measured.put("voiceTruePeakDb", round2(voice.truePeakDb()));
        measured.put("voiceGainDb", round2(attempt.voiceGain()));
        measured.put("finalLufs", round2(attempt.measured().integratedLufs()));
        measured.put("finalTruePeakDb", round2(attempt.measured().truePeakDb()));
        measured.put("targetLufs", AudioLoudness.TARGET_LUFS);
        measured.put("toleranceLufs", AudioLoudness.TOLERANCE_LUFS);
        measured.put("truePeakLimitDb", AudioLoudness.MAX_TRUE_PEAK);
        measured.put("bgmDropped", bgmDropped);
        measured.put("sfxMixed", withSfx);
        measured.put("mixAttempts", attempt.attempts());
        // 选曲留痕：一首配乐凭什么被选中，事后要能逐条复核
        if (pick != null) {
            measured.put("bgmKey", pick.track() == null ? null : pick.track().key());
            measured.put("bgmReason", pick.reason());
            measured.putAll(pick.measured());
        }
        if (bgm != null && !bgmDropped && bgmMeasured != null) {
            measured.put("bgmLufs", round2(bgmMeasured.integratedLufs()));
            measured.put("bgmGainDb", round2(bgmGain));
            measured.put("bgmOffsetLufs", AudioLoudness.BGM_OFFSET_LUFS);
        }
        String reason = bgmDropped
            ? "配乐导致响度超标，去掉配乐重混后达标；配乐是加分项，不为它卡住出片"
            : "旁白与配乐按响度口径对齐，成片整体响度与真峰值均在交付范围内";
        return new Gate("audio_mix", FINAL_FILE + ":loudness", reason, measured);
    }

    private static String fmt(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    /**
     * 选配乐：曲库清单 + 编辑线过滤 + 故事计划情绪节奏加权 + 权重摇号。
     *
     * <p>摇号的种子取自冻结输入，不用 {@code Math.random}：同一个任务重跑必须选到同一首，
     * 否则成片指纹、审稿结论与返工基线三处都会对不上。
     *
     * <p>曲库不可用（没有目录/没有清单/清单写坏）时退化成无配乐，
     * 并把原因写进闸报告——降级要留痕，不能静悄悄。
     */
    private BgmCatalog.Pick pickBgm(JsonNode script, JsonNode input, ProductionRunVo run) {
        Path dir = BgmCatalog.directory();
        if (dir == null) {
            return new BgmCatalog.Pick(null, null, "配乐目录不存在，本期无配乐", new TreeMap<>());
        }
        BgmCatalog.Story story = BgmCatalog.storyOf(script, lineOf(input));
        return BgmCatalog.pick(BgmCatalog.load(dir), dir, story.line(), story.emotion(), story.rhythm(),
            run.inputJson());
    }

    private Path contactSheet(ProductionRunVo run, Path work, Path finalFile, Path sheet,
                              List<DirectorVisualRework.Sample> samples) {
        String frames = samples.isEmpty()
            ? "fps=1/" + Math.max(1, (int) Math.floor(probeDuration(run, finalFile) / SHEET_TILES))
            : "select=" + samples.stream().map(sample -> "eq(n\\," + sample.frame() + ")")
                .collect(java.util.stream.Collectors.joining("+"));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg",
            List.of("-y", "-loglevel", "error", "-i", relative(work, finalFile), "-vf",
                frames + ",scale=360:-2,tile=3x2", "-frames:v", "1", relative(work, sheet)), work, PACKAGE_TIMEOUT));
        if (result.exitCode() != 0 || size(sheet) <= 0) {
            throw new HeronBusinessException(503, "news_contact_sheet_failed", "联络表生成失败");
        }
        return sheet;
    }

    private static String relative(Path work, Path path) {
        return work.relativize(path).toString().replace('\\', '/');
    }

    private double probeDuration(ProductionRunVo run, Path file) {
        String relative = work(run).relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1", relative),
            work(run), PROBE_TIMEOUT));
        Matcher matcher = Pattern.compile("duration=([0-9.]+)").matcher(result.stdout());
        if (result.exitCode() != 0 || !matcher.find()) {
            throw new HeronBusinessException(409, "news_probe_failed", "读不到时长：" + safeTail(result.stderr()));
        }
        return Double.parseDouble(matcher.group(1));
    }

    private String cliEntry() {
        String configured = properties.getHyperframes();
        if (configured == null || configured.isBlank() || !Files.isRegularFile(Path.of(configured))) {
            throw new HeronBusinessException(503, "news_tool_missing", "未配置或找不到 hyperframes CLI 入口");
        }
        return Path.of(configured).toAbsolutePath().normalize().toString();
    }

    // ---------- 变量映射与时间轴补丁 ----------

    /** 稿件 → 变量：只搬文本；时长一律走磁盘补丁，因为根时长编译期就读一次。 */
    static String templateVariables(JsonNode script) {
        return templateVariables(script, Map.of(), java.util.Set.of());
    }

    /**
     * @param images 场景序号 → 已落到工作目录的相对文件名；没有图的场景不出键，
     *               模板据此决定是否展开图片区，避免留一块空白。
     */
    static String templateVariables(JsonNode script, Map<String, String> images) {
        return templateVariables(script, images, java.util.Set.of());
    }

    /**
     * @param declared 模板声明过的变量名；空集表示模板没声明能力，全部输出（旧模板与预览用）。
     *                 非空时只输出声明过的键——多给的变量会被 --strict-variables 判成未声明并中止渲染。
     */
    static String templateVariables(JsonNode script, Map<String, String> images, java.util.Set<String> declared) {
        JsonNode scenes = script.path("scenes");
        Map<String, Object> vars = new TreeMap<>();
        vars.put("title", script.path("title").asString(""));
        vars.put("closing", script.path("closing").asString(""));
        vars.put("source_name", script.path("source_name").asString(""));
        vars.put("scene_count", Math.min(scenes.size(), MAX_SCENE_SLOTS));
        for (int index = 0; index < Math.min(scenes.size(), MAX_SCENE_SLOTS); index++) {
            JsonNode scene = scenes.get(index);
            vars.put("scene" + (index + 1) + "_lead", scene.path("lead").asString(""));
            vars.put("scene" + (index + 1) + "_body", scene.path("body").asString(""));
            // 模板为每个场景都声明了 data-var-src，--strict-variables 要求全部有值。
            // 无图的场景给空串，模板的 has-image 判断据此保持关闭。
            vars.put("scene" + (index + 1) + "_image", images.getOrDefault(String.valueOf(index + 1), ""));
            // 场景类型是渲染信号，不只是契约层的分类：模板按它切换版式变体
            vars.put("scene" + (index + 1) + "_type", scene.path("scene_type").asString(""));
            // 分镜另两件：叙事节点给版式一条辅线，上屏文字与旁白解耦
            vars.put("scene" + (index + 1) + "_beat", scene.path("beat").asString(""));
            vars.put("scene" + (index + 1) + "_text", scene.path("on_screen_text").asString(""));
        }
        if (!declared.isEmpty()) {
            vars.keySet().retainAll(declared);
        }
        return JSON.writeValueAsString(vars);
    }

    /**
     * 把稿子里引用的图片落到工作目录。只认稿子里声明的 asset_id → source_url 对应关系，
     * 不扫描正文重抽一遍：那样会和模型写稿时看到的清单不一致，出现「稿子里说的图」和「画面上放的图」不是同一张。
     *
     * <p>取不到的图直接跳过，场景退化成纯文字。一张配图失败不该让整条片子报废。
     */
    private Map<String, String> materializeImages(Path work, JsonNode script, JsonNode input,
                                                   List<MediaAssetSelectionVo> selections) {
        Map<String, String> resolved = new TreeMap<>();
        JsonNode scenes = script.path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            resolved.putAll(resolveSceneAssets(work, scenes.get(index), index + 1, input, selections));
        }
        return resolved;
    }

    /** V5：只解析传入场景；返工未改屏不调用 selector，也不重新下载素材。 */
    protected Map<String, String> resolveSceneAssets(Path work, JsonNode scene, int sceneNumber, JsonNode input,
                                                      List<MediaAssetSelectionVo> selections) {
        if (mediaAssets == null) {
            return legacySceneAssets(work, scene, sceneNumber, input);
        }
        if (!"IMAGE".equalsIgnoreCase(scene.path("scene_type").asString(""))) {
            return Map.of();
        }
        String sceneId = scene.path("scene_id").asString("");
        JsonNode visual = scene.path("visual");
        JsonNode firstAsset = visual.path("assets").isArray() && !visual.path("assets").isEmpty()
            ? visual.path("assets").get(0) : visual;
        MediaAssetSelectionBo request = new MediaAssetSelectionBo(sceneId, sceneNumber,
            "IMAGE", assetId(scene), visual.path("orientation").asString("PORTRAIT"),
            keywords(visual.path("keywords")), firstAsset.path("source_url").asString(""));
        MediaAssetSelectionVo selected = mediaAssets.selectForScene(request);
        if (selected == null || selected.getGate() == null || !selected.getGate().isPassed()) {
            throw new HeronBusinessException(409, "news_media_asset_rejected", "场景素材选择没有返回通过的 assets 闸");
        }
        selections.add(selected);
        if (!"SELECTED".equals(selected.getStatus())) {
            selected.setImageValue("");
            return Map.of(String.valueOf(sceneNumber), "");
        }
        try {
            byte[] bytes = downloadSelectedAsset(selected);
            if (bytes.length > MediaAssetStorage.MAX_BYTES || !DigestUtil.sha256Hex(bytes)
                .equalsIgnoreCase(selected.getContentSha256())) {
                throw new HeronBusinessException(409, "news_media_asset_integrity", "选中素材的大小上限或 SHA-256 不匹配");
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != selected.getWidth() || image.getHeight() != selected.getHeight()
                || (!png(bytes) && !jpeg(bytes))) {
                throw new HeronBusinessException(409, "news_media_asset_integrity", "选中素材不是声明尺寸的 PNG/JPEG 图像");
            }
            String name = "scene" + sceneNumber + (jpeg(bytes) ? ".jpg" : ".png");
            Files.write(work.resolve(name), bytes);
            selected.setImageValue(name);
            return Map.of(String.valueOf(sceneNumber), name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "news_media_asset_download_interrupted", "素材下载被中断", e);
        } catch (IOException | RuntimeException e) {
            String reason = "选中素材下载或完整性校验失败，场景显式退回纯文字：" + e.getClass().getSimpleName();
            selected.setStatus("TEXT_ONLY");
            selected.setReasonCode("asset_download_failed");
            selected.setReason(reason);
            selected.setImageValue("");
            selected.setGate(new MediaAssetGateVo("scene:" + sceneId, "asset_download_failed", reason,
                true, null, true));
            log.warn("场景 {} 素材回退纯文字: {}", sceneId, e.getClass().getSimpleName());
            return Map.of(String.valueOf(sceneNumber), "");
        }
    }

    /** 可替换的下载边界；生产只接受 HTTP(S)，不跟随重定向，并限制到素材登记的大小。 */
    protected byte[] downloadSelectedAsset(MediaAssetSelectionVo selected) throws IOException, InterruptedException {
        URI uri = URI.create(selected.getImageValue());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null) {
            throw new IOException("OSS 地址非法");
        }
        HttpResponse<InputStream> response = assetHttp.send(HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("OSS 返回 " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            return body.readNBytes(Math.toIntExact(MediaAssetStorage.MAX_BYTES) + 1);
        }
    }

    private static boolean png(byte[] bytes) {
        return bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
            && bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
    }

    private static boolean jpeg(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
    }

    private static Long assetId(JsonNode scene) {
        JsonNode visual = scene.path("visual");
        JsonNode firstAsset = visual.path("assets").isArray() && !visual.path("assets").isEmpty()
            ? visual.path("assets").get(0) : visual;
        String value = visual.path("mediaAssetId").asString(visual.path("media_asset_id").asString(
            visual.path("asset_id").asString(firstAsset.path("media_asset_id").asString(
                firstAsset.path("asset_id").asString("")))));
        if (value.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new HeronBusinessException(400, "news_media_asset_id_invalid", "显式素材 ID 非法，拒绝自动选材");
            }
            return id;
        } catch (NumberFormatException e) {
            if (firstAsset.path("asset_id").asString("").equals(value) && firstAsset.path("source_url").isTextual()) {
                return null;
            }
            throw new HeronBusinessException(400, "news_media_asset_id_invalid", "显式素材 ID 非法，拒绝自动选材");
        }
    }

    private static List<String> keywords(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asString("");
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private static MediaAssetSelectionVo restoreMediaSelection(JsonNode node) {
        MediaAssetSelectionVo selected = new MediaAssetSelectionVo();
        selected.setSceneId(node.path("sceneId").asString(""));
        selected.setSceneNumber(node.path("sceneNumber").isIntegralNumber() ? node.path("sceneNumber").asInt() : null);
        selected.setSceneType(node.path("sceneType").asString(""));
        selected.setStatus(node.path("status").asString(""));
        selected.setReasonCode(node.path("reasonCode").asString(""));
        selected.setReason(node.path("reason").asString(""));
        selected.setImageVariable(node.path("imageVariable").asString(""));
        selected.setImageValue(node.path("imageValue").asString(""));
        selected.setAssetId(node.path("assetId").isIntegralNumber() ? node.path("assetId").asLong() : null);
        selected.setAssetName(node.path("assetName").asString(""));
        selected.setLicenseCode(node.path("licenseCode").asString(""));
        selected.setLicenseEvidenceUri(node.path("licenseEvidenceUri").asString(""));
        selected.setAttribution(node.path("attribution").asString(""));
        selected.setSourceUri(node.path("sourceUri").asString(""));
        selected.setContentSha256(node.path("contentSha256").asString(""));
        selected.setWidth(node.path("width").isIntegralNumber() ? node.path("width").asInt() : null);
        selected.setHeight(node.path("height").isIntegralNumber() ? node.path("height").asInt() : null);
        selected.setOssConfigKey(node.path("ossConfigKey").asString(""));
        selected.setOssKey(node.path("ossKey").asString(""));
        JsonNode measured = node.path("gate").path("measured");
        selected.setGate(new MediaAssetGateVo(node.path("gate").path("inputLocator").asString(""),
            selected.getReasonCode(), selected.getReason(), measured.path("licenseChecked").asBoolean(false),
            selected.getAssetId(), measured.path("fallbackToText").asBoolean(false)));
        return selected;
    }

    private static Gate mediaAssetGate(MediaAssetSelectionVo selection) {
        JsonNode gate = JSON.readTree(JSON.writeValueAsString(selection.getGate()));
        boolean attributionOk = !MediaAssetLicensePolicy.requiresAttribution(selection.getLicenseCode())
            || (selection.getAttribution() != null && !selection.getAttribution().isBlank());
        String reason = attributionOk ? gate.path("reason").asString("") : "CC_BY/CC_BY_SA 素材缺少署名，拒绝发布";
        return new Gate("assets", gate.path("inputLocator").asString(""), reason,
            JSON.convertValue(gate.path("measured"), Map.class), gate.path("passed").asBoolean(false) && attributionOk);
    }

    private Map<String, String> legacySceneAssets(Path work, JsonNode scene, int sceneNumber, JsonNode input) {
        Map<String, String> resolved = new TreeMap<>();
        JsonNode assets = scene.path("visual").path("assets");
        if (!assets.isArray() || assets.isEmpty()) {
            return resolved;
        }
        JsonNode first = assets.get(0);
        String url = first.path("source_url").asString("");
        if (url.isBlank()) {
            url = indexEvidenceAssets(input).getOrDefault(first.path("asset_id").asString(""), "");
        }
        if (url.isBlank()) {
            return resolved;
        }
        try {
            var bytes = fetcher.fetchImage(java.net.URI.create(url));
            if (bytes.isEmpty()) {
                return resolved;
            }
            String name = "scene" + sceneNumber + imageExt(url);
            Files.write(work.resolve(name), bytes.get());
            resolved.put(String.valueOf(sceneNumber), name);
        } catch (RuntimeException | IOException e) {
            log.warn("场景 {} 的配图取不到，退化成纯文字: {}", sceneNumber, e.getMessage());
        }
        return resolved;
    }

    /** 证据里的图片清单：asset_id → source_url，模型只回 asset_id 时用它补地址。 */
    private static Map<String, String> indexEvidenceAssets(JsonNode input) {
        Map<String, String> byAsset = new java.util.HashMap<>();
        JsonNode assets = input.path("evidence").path("assets");
        if (assets.isArray()) {
            for (JsonNode asset : assets) {
                byAsset.put(asset.path("asset_id").asString(""), asset.path("source_url").asString(""));
            }
        }
        return byAsset;
    }

    private static String imageExt(String url) {
        String lower = url.toLowerCase();
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        for (String ext : List.of(".png", ".jpg", ".jpeg", ".webp", ".gif")) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return ".jpg";
    }

    /** 数据看板变量：沿用文章线文本变量，再补图表类型与整份快照（模板自己解析）。 */
    static String dataTemplateVariables(JsonNode script, JsonNode dataset) {
        return dataTemplateVariables(script, dataset, java.util.Set.of());
    }

    static String dataTemplateVariables(JsonNode script, JsonNode dataset, java.util.Set<String> declared) {
        return dataTemplateVariables(script, dataset, null, declared);
    }

    static String dataTemplateVariables(JsonNode script, JsonNode dataset, JsonNode trend,
                                        java.util.Set<String> declared) {
        ObjectNode vars = (ObjectNode) JSON.readTree(templateVariables(DatasetPlaceholders.resolve(script, dataset, trend),
            Map.of(), declared));
        vars.put("chart", DatasetScriptContract.chartFor(dataset.path("shape").asString("")));
        vars.put("dataset_json", dataset.toString());
        for (int index = 0; index < script.path("scenes").size(); index++) {
            vars.put("scene" + (index + 1) + "_focus", JSON.writeValueAsString(
                DatasetVisualFocus.forScene(script.path("scenes").get(index), dataset)));
        }
        if (!declared.isEmpty()) {
            java.util.List<String> extra = new ArrayList<>();
            for (String key : vars.propertyNames()) {
                if (!declared.contains(key)) {
                    extra.add(key);
                }
            }
            extra.forEach(vars::remove);
        }
        return JSON.writeValueAsString(vars);
    }

    /**
     * 模板声明过的变量名，取自 index.html 的 data-composition-variables。
     * 读不到就返回空集，交给调用方按「未声明」处理（不因为读不到就拒绝渲染）。
     */
    static java.util.Set<String> declaredVariables(java.nio.file.Path templateDir) {
        java.nio.file.Path index = templateDir.resolve("index.html");
        if (!java.nio.file.Files.isRegularFile(index)) {
            return java.util.Set.of();
        }
        String marker = "data-composition-variables='";
        try {
            String html = java.nio.file.Files.readString(index, java.nio.charset.StandardCharsets.UTF_8);
            int at = html.indexOf(marker);
            if (at < 0) {
                return java.util.Set.of();
            }
            int start = at + marker.length();
            int end = html.indexOf('\'', start);
            if (end < 0) {
                return java.util.Set.of();
            }
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            JSON.readTree(html.substring(start, end)).forEach(item -> {
                String id = item.path("id").asString("");
                if (!id.isBlank()) {
                    out.add(id);
                }
            });
            return out;
        } catch (RuntimeException | java.io.IOException e) {
            return java.util.Set.of();
        }
    }

    /** 把真实输入写入工作副本的默认声明，让不支持 variables-file 的 check 也验证同一份内容。 */
    static String bindVariablesString(String html, String variablesJson) {
        Matcher attribute = Pattern.compile("\\bdata-composition-variables\\s*=\\s*(['\"])(.*?)\\1", Pattern.DOTALL)
            .matcher(html);
        if (!attribute.find()) {
            throw new HeronBusinessException(409, "news_variables_invalid", "模板没有变量声明，无法检查真实稿件");
        }
        JsonNode declarations;
        JsonNode values;
        try {
            declarations = JSON.readTree(EscapeUtil.unescapeHtml4(attribute.group(2)));
            values = JSON.readTree(variablesJson);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "news_variables_invalid", "模板声明或真实变量不是有效 JSON");
        }
        if (declarations == null || values == null || !declarations.isArray() || !values.isObject()) {
            throw new HeronBusinessException(409, "news_variables_invalid", "模板声明必须为数组，真实变量必须为对象");
        }
        Set<String> remaining = new HashSet<>(values.propertyNames());
        for (JsonNode declaration : declarations) {
            if (!declaration.isObject()) {
                throw new HeronBusinessException(409, "news_variables_invalid", "模板变量声明不是对象");
            }
            String id = declaration.path("id").asString("");
            if (values.has(id)) {
                ((ObjectNode) declaration).set("default", values.get(id));
                remaining.remove(id);
            }
        }
        if (!remaining.isEmpty()) {
            throw new HeronBusinessException(409, "news_variables_invalid", "模板未声明真实变量：" + String.join("、", remaining));
        }
        String encoded = EscapeUtil.escapeHtml4(JSON.writeValueAsString(declarations)).replace("'", "&#39;");
        return html.substring(0, attribute.start(2)) + encoded + html.substring(attribute.end(2));
    }

    /** 使用配音字幕的真实句界写入时间轴，声明时长只作写稿预算。 */
    static String timeCompositionString(String html, JsonNode script, double totalSeconds, String vtt) {
        return applyTimeline(html, NewsNarrationTiming.align(script, JSON.readTree(subtitleJson(vtt)),
            Math.round(totalSeconds * 1000)), totalSeconds);
    }

    private static String applyTimeline(String html, List<NewsNarrationTiming.Span> timeline, double totalSeconds) {
        String patched = replaceTiming(html, "root", 0, totalSeconds);
        patched = replaceTiming(patched, "narration", 0, totalSeconds);
        // 常驻层也要跟成片走：模板默认 45 秒，长片超过 45 秒会整层消失。
        patched = replaceTimingIfPresent(patched, "title-card", 0, totalSeconds);
        patched = replaceTimingIfPresent(patched, "chart-slot", 0, totalSeconds);
        for (NewsNarrationTiming.Span span : timeline) {
            patched = replaceTiming(patched, span.id(), span.startMs() / 1000.0,
                (span.endMs() - span.startMs()) / 1000.0);
        }
        // 没有口播的槽位不占用成片窗口，scene_count 也已绑定为真实段数。
        for (int index = timeline.size() - 1; index < MAX_SCENE_SLOTS; index++) {
            patched = replaceTiming(patched, "scene-" + (index + 1), totalSeconds, 0.01);
        }
        return patched;
    }

    private static String replaceTiming(String html, String id, double start, double duration) {
        Matcher tag = Pattern.compile("<[a-z]+[^<>]*\\bid=\"" + id + "\"[^<>]*>").matcher(html);
        if (!tag.find()) {
            throw new HeronBusinessException(503, "news_template_broken", "模板里找不到时间轴槽位 " + id);
        }
        String open = tag.group();
        String rewritten = open.replaceAll("data-duration=\"[0-9.]+\"", "data-duration=\"" + number(duration) + "\"");
        rewritten = rewritten.replaceAll("data-start=\"[0-9.]+\"", "data-start=\"" + number(start) + "\"");
        if (rewritten.equals(open) && !open.contains("data-duration")) {
            throw new HeronBusinessException(503, "news_template_broken", "槽位 " + id + " 缺少时长属性");
        }
        return html.substring(0, tag.start()) + rewritten + html.substring(tag.end());
    }

    /** 模板可能没有这个常驻层（文章模板没有 chart-slot），存在才补时间轴。 */
    private static String replaceTimingIfPresent(String html, String id, double start, double duration) {
        return html.contains("id=\"" + id + "\"") ? replaceTiming(html, id, start, duration) : html;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100d;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private void timeComposition(Path composition, List<NewsNarrationTiming.Span> timeline, double totalSeconds) {
        write(composition.resolve("index.html"),
            applyTimeline(read(composition.resolve("index.html")), timeline, totalSeconds));
    }

    /** 模板根目录下的单个模板；key 只允许锁定的字符集，避免拼出目录穿越。 */
    private Path templateDir(String templateKey) {
        Path root = properties.getNewsTemplate();
        if (root == null || templateKey == null || !templateKey.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new HeronBusinessException(503, "news_template_missing", "找不到锁定的 composition 模板目录");
        }
        Path dir = root.toAbsolutePath().normalize().resolve(templateKey).normalize();
        if (!dir.startsWith(root.toAbsolutePath().normalize()) || !Files.isDirectory(dir)) {
            throw new HeronBusinessException(503, "news_template_missing", "找不到锁定的 composition 模板目录：" + templateKey);
        }
        return dir;
    }

    /**
     * Profile 根目录：字幕/封面画布与令牌的真源（导演架构 S0b）。
     * 冻结快照 assets.styleKey 指向风格 id（article/data-board/...）；缺省回退 article（解说线既有令牌）。
     */
    private Path profileDir(JsonNode input) {
        String styleKey = input.path("assets").path("styleKey").asString("article");
        Path root = properties.getNewsProfile();
        if (root == null || !styleKey.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new HeronBusinessException(503, "news_profile_missing", "Profile 根目录未配置或风格 id 非法：" + styleKey);
        }
        Path dir = root.toAbsolutePath().normalize().resolve(styleKey).normalize();
        if (!dir.startsWith(root.toAbsolutePath().normalize()) || !Files.isDirectory(dir)) {
            throw new HeronBusinessException(503, "news_profile_missing", "找不到 Profile 目录：" + styleKey);
        }
        return dir;
    }

    /** 冻结快照 kind → 编辑线（BgmCatalog 按线过滤用），与 NewsProductionDomain.kindOfLine 同一口径。 */
    private static String lineOf(JsonNode input) {
        return switch (input.path("kind").asString("")) {
            case "data_board" -> EditorialSelection.DATA_BOARD;
            case "trend" -> EditorialSelection.TREND_INSIGHT;
            case "knowledge_explainer" -> EditorialSelection.KNOWLEDGE_EXPLAINER;
            default -> EditorialSelection.NEWS_EXPLAINER;
        };
    }

    private static int[] templateSize(Path templateDir) {
        try {
            JsonNode config = JSON.readTree(read(templateDir.resolve("template.config.json")));
            int width = config.path("width").asInt(0);
            int height = config.path("height").asInt(0);
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
                throw new HeronBusinessException(409, "news_decode_failed", "模板画布尺寸声明不合法");
            }
            return new int[]{width, height};
        } catch (RuntimeException e) {
            if (e instanceof HeronBusinessException business) {
                throw business;
            }
            throw new HeronBusinessException(409, "news_decode_failed", "模板画布尺寸声明不可读", e);
        }
    }

    private Path copyTemplate(Path work, Path template) {
        if (template == null || !Files.isDirectory(template)) {
            throw new HeronBusinessException(503, "news_template_missing", "找不到锁定的 composition 模板目录");
        }
        template = template.toAbsolutePath().normalize();
        Path composition = work.resolve(COMPOSITION_DIR);
        try (Stream<Path> paths = Files.walk(template)) {
            for (Path source : paths.toList()) {
                Path relative = template.relativize(source);
                // 模板里可能留着工具自己的缓存目录，不进工作副本，也不进资产清单。
                if (!relative.toString().isEmpty() && relative.getName(0).toString().startsWith(".")) {
                    continue;
                }
                Path target = composition.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        copySharedFont(composition);
        return composition;
    }

    private void copySharedFont(Path composition) {
        // 模板自带的 fonts/ 优先，只有模板目录没带字体时才补模板根同级的共用字体。
        if (Files.isRegularFile(composition.resolve("fonts").resolve(FONT_NAME))) {
            return;
        }
        Path root = properties.getNewsTemplate();
        Path dir = root == null ? null : root.toAbsolutePath().normalize().getParent();
        Path font = dir == null ? null : dir.resolve("fonts").resolve(FONT_NAME);
        if (font == null || !Files.isRegularFile(font)) {
            throw new HeronBusinessException(503, "news_font_missing", "找不到模板共用字体：" + font);
        }
        try {
            Path target = composition.resolve("fonts").resolve(FONT_NAME);
            Files.createDirectories(target.getParent());
            Files.copy(font, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String assetManifest(Path composition, JsonNode locked) {
        return assetManifest(composition, locked, List.of(), null);
    }

    private static String assetManifest(Path composition, JsonNode locked, List<MediaAssetSelectionVo> selections,
                                        JsonNode inherited) {
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("locked", locked);
        Path storyboard = composition.resolve("storyboard.json");
        if (locked.has("profileId")) {
            manifest.put("director", JSON.readTree(read(storyboard)));
        }
        Map<String, Map<String, Object>> files = new TreeMap<>();
        for (Map<String, Object> file : fileHashes(composition)) {
            files.put((String) file.get("path"), file);
        }
        Set<String> reused = selections.stream().map(MediaAssetSelectionVo::getImageValue)
            .filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        if (inherited != null && inherited.path("files").isArray()) {
            for (JsonNode file : inherited.path("files")) {
                String path = file.path("path").asString("");
                if (reused.contains(path) && !files.containsKey(path)) {
                    files.put(path, JSON.convertValue(file, Map.class));
                }
            }
        }
        manifest.put("files", new ArrayList<>(files.values()));
        manifest.put("mediaSelections", JSON.readTree(JSON.writeValueAsString(selections)));
        return JSON.writeValueAsString(manifest);
    }

    private static List<Map<String, Object>> fileHashes(Path composition) {
        List<Map<String, Object>> hashes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(composition)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                Map<String, Object> entry = new TreeMap<>();
                entry.put("path", composition.relativize(file).toString().replace('\\', '/'));
                entry.put("sha256", sha256(file));
                entry.put("bytes", Files.size(file));
                hashes.add(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hashes;
    }

    static String subtitleJson(String vtt) {
        List<Map<String, Object>> cues = new ArrayList<>();
        Matcher matcher = CUE.matcher(vtt);
        while (matcher.find()) {
            Map<String, Object> cue = new LinkedHashMap<>();
            cue.put("startMs", millis(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
            cue.put("endMs", millis(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8)));
            cue.put("text", matcher.group(9).trim());
            cues.add(cue);
        }
        return JSON.writeValueAsString(cues);
    }

    private static long millis(String hours, String minutes, String seconds, String fraction) {
        return (Long.parseLong(hours) * 3600 + Long.parseLong(minutes) * 60 + Long.parseLong(seconds)) * 1000
            + Long.parseLong((fraction + "000").substring(0, 3));
    }

    // ---------- 小工具 ----------


    /** 上一版结果里的 script 必须与已校验产物逐字段完全相等，不能只验证 manifest 自洽。 */





    private static double rate(String fraction) {
        String[] parts = fraction.split("/");
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length > 1 ? Double.parseDouble(parts[1]) : 1;
            return denominator <= 0 ? 0 : round2(numerator / denominator);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 联络表按 3x2 分格逐格取样；整格只剩一两种颜色即判为空白帧。 */
    private static int blankCells(Path sheet) {
        try {
            return blankCells(ImageIO.read(sheet.toFile()), 3, 2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int blankSingleFrame(Path frame) {
        try {
            return blankCells(ImageIO.read(frame.toFile()), 1, 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int blankCells(BufferedImage image, int columns, int rows) {
        if (image == null) {
            throw new HeronBusinessException(409, "news_contact_sheet_unreadable", "画面帧不可读");
        }
        int cellWidth = image.getWidth() / columns;
        int cellHeight = image.getHeight() / rows;
        int blank = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                Set<Integer> colors = new HashSet<>();
                for (int y = row * cellHeight + 4; y < (row + 1) * cellHeight - 4 && colors.size() < 3; y += 17) {
                    for (int x = column * cellWidth + 4; x < (column + 1) * cellWidth - 4 && colors.size() < 3; x += 17) {
                        colors.add(image.getRGB(x, y) & 0xFFFFFF);
                    }
                }
                if (colors.size() < 3) {
                    blank++;
                }
            }
        }
        return blank;
    }

    private static String safeHead(String text) {
        String flat = flat(text);
        return flat.length() > 1400 ? flat.substring(0, 1400) : flat;
    }

    private static String flat(String text) {
        return text == null ? "" : text.replaceAll("[/\\\\]", "-").replaceAll("\\s+", " ").trim();
    }

    private static String safeTail(String text) {
        String flat = flat(text);
        return flat.length() > 900 ? flat.substring(flat.length() - 900) : flat;
    }

    private static String sha256(Path file) {
        try (var input = Files.newInputStream(file)) {
            return DigestUtil.sha256Hex(input);
        } catch (IOException e) {
            return "";
        }
    }

    private static long size(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path write(Path file, String content) {
        try {
            return Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
