package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.EscapeUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.ProductionStageReporter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.List;
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
public class NewsProductionPipeline implements IProductionPipeline {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VOICE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(600);
    private static final Duration PACKAGE_TIMEOUT = Duration.ofSeconds(120);
    private static final String COMPOSITION_DIR = "composition";
    private static final String VOICE_FILE = "narration.mp3";
    private static final String SUBTITLES_FILE = "subs.vtt";
    private static final String VARS_FILE = "vars.json";
    private static final String RAW_FILE = "raw.mp4";
    private static final String FINAL_FILE = "final.mp4";
    private static final String SHEET_FILE = "contact.png";
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

    private final NewsScriptAuthor author;
    private final ProductionProcessService processes;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final ProductionStageReporter stages;
    private final DatasetRouter router;
    private final DatasetFetchService datasets;
    private final DatasetScriptAuthor datasetAuthor;
    private final EditorialReviewer reviewer;
    private final QualityReviewer qualityReviewer;
    private final NewsSourceFetcher fetcher;
    private final FrozenContextService contexts;

    public NewsProductionPipeline(NewsScriptAuthor author, ProductionProcessService processes,
                                  ProductionArtifactService artifacts, HeronProductionProperties properties,
                                  ProductionStageReporter stages, DatasetRouter router,
                                  DatasetFetchService datasets, DatasetScriptAuthor datasetAuthor,
                                  EditorialReviewer reviewer, QualityReviewer qualityReviewer,
                                  NewsSourceFetcher fetcher, FrozenContextService contexts) {
        this.author = author;
        this.processes = processes;
        this.artifacts = artifacts;
        this.properties = properties;
        this.stages = stages;
        this.router = router;
        this.datasets = datasets;
        this.datasetAuthor = datasetAuthor;
        this.reviewer = reviewer;
        this.qualityReviewer = qualityReviewer;
        this.fetcher = fetcher;
        this.contexts = contexts;
    }

    /**
     * 冻结输入的读取口径：有上下文行的任务必须与冻结 hash 吻合，没有的是迁移前的老任务。
     * 校验失败在这里就抛，不会走到任何取数/写稿步骤。
     */
    private JsonNode frozenInput(ProductionRunVo run) {
        contexts.verify(run.taskId(), run.inputJson());
        return JSON.readTree(run.inputJson());
    }

    @Override
    public ProductionResultVo execute(ProductionRunVo run) {
        JsonNode input = frozenInput(run);
        List<Gate> gates = new ArrayList<>();
        // 阶段回报只给操作员看进度；租约已易主时返回 false，交给 runner 收尾，这里不因此失败。
        boolean dataBoard = "data_board".equals(input.path("kind").asString(""));
        if (!dataBoard && !properties.isArticleEnabled()) {
            throw new HeronBusinessException(409, "article_disabled", "文章新闻路径已下线，只生产数据看板");
        }
        stages.beginStage(run, "script", 5);
        DatasetFetchService.Result fetched = null;
        JsonNode dataset = null;
        String scriptJson;
        String hookType = null;
        ReviewOutcome review;
        if (dataBoard) {
            DatasetRouter.Route route = frozenRoute(run, input);
            // 取数口径随冻结输入走：选题当时用多少点，这里就用多少点。
            // 过去固定取 30 点，与选题分析的窗口必然不同，两边归约出的 sha 也永远比不了。
            DatasetWindowContract.Window window = DatasetWindowContract.windowOf(input);
            List<String> incomplete = DatasetWindowContract.windowViolations(window);
            if (!incomplete.isEmpty()) {
                throw new HeronBusinessException(409, "dataset_window_mismatch",
                    "冻结的取数口径不完整，不去取数：" + String.join("；", incomplete));
            }
            fetched = datasets.fetch(run, route.sourceKey(), DatasetWindowContract.pointsOf(input));
            dataset = JSON.readTree(fetched.json());
            gates.add(dataGate(dataset, window));
            gates.add(datasetEvidenceGate(dataset));
            DatasetScriptAuthor.Result draft = datasetAuthor.author(run, fetched.json(), route.digest());
            scriptJson = draft.script();
            hookType = draft.hookType();
            review = reviewScript(run, scriptJson, dataset.path("facts").toString(), fetched, true, hookType);
        } else {
            // 正文由作者从冻结层取：首次抓取并落库，重试读同一份，写稿阶段不再二次出网
            scriptJson = author.author(run);
            // 解说线此前完全没有审稿，自动确认会把没审过的片子直接发出去
            review = reviewScript(run, scriptJson, evidenceQuotes(input), null, false, null);
        }
        scriptJson = review.script;
        hookType = review.hookType;
        JsonNode script = JSON.readTree(scriptJson);
        gates.add(contractGate(script, dataset, dataBoard));
        if (!dataBoard) {
            gates.add(evidenceGate(script));
        }

        Path work = artifacts.prepareWorkDirectory(run);
        Path templateDir = templateDir(input.path("assets").path("templateKey").asString(""));
        Path composition = copyTemplate(work, templateDir);
        // 占位稿入库供 data 闸复核；TTS 与模板用解析后的可读文本。
        JsonNode renderScript = dataBoard ? DatasetPlaceholders.resolve(script, dataset) : script;
        // 解说线的图片场景：把稿子里引用的资产下载进工作目录，再以 sceneN_image 变量交给模板
        Map<String, String> images = dataBoard ? Map.of() : materializeImages(composition, renderScript, input);
        // 只给模板自己声明过的变量：--strict-variables 会把「多给的变量」判成未声明并直接中止渲染
        java.util.Set<String> declared = declaredVariables(templateDir);
        String variables = dataBoard
            ? dataTemplateVariables(script, dataset, declared)
            : templateVariables(renderScript, images, declared);
        write(work.resolve(VARS_FILE), variables);
        gates.add(assetsGate(composition, input.path("assets"), templateDir));
        gates.add(sceneTypeGate(script, templateDir));
        write(composition.resolve("index.html"), bindVariablesString(read(composition.resolve("index.html")), variables));

        Path voice = composition.resolve(VOICE_FILE);
        stages.beginStage(run, "voice", 25);
        speak(run, work, renderScript, input.path("assets").path("voiceKey").asString(""));
        double audioSeconds = probeDuration(run, voice);
        gates.add(voiceGate(voice, audioSeconds));
        String subtitles = subtitleJson(read(composition.resolve(SUBTITLES_FILE)));
        List<NewsNarrationTiming.Span> timeline = NewsNarrationTiming.align(renderScript, JSON.readTree(subtitles),
            Math.round(audioSeconds * 1000));
        gates.add(subtitleGate(composition.resolve(SUBTITLES_FILE), audioSeconds, timeline));

        stages.beginStage(run, "render", 45);
        timeComposition(composition, timeline, audioSeconds);
        JsonNode check = checkComposition(run, work);
        render(run, work);
        gates.add(renderGate(work.resolve(RAW_FILE)));
        gates.add(decodeGate(decode(run, work.resolve(RAW_FILE)), audioSeconds));

        stages.beginStage(run, "finalize", 85);
        Path finalFile = tagAigc(run, work, timeline.get(timeline.size() - 1));
        gates.add(aigcMetadataGate(run, finalFile));
        Path sheet = contactSheet(run, work, finalFile);
        gates.add(visualGate(check, sheet));
        gates.add(aigcVisibleGate(check, composition));
        gates.sort((left, right) -> left.gate().compareTo(right.gate()));

        String checks = gateJson(gates);
        List<ProductionArtifactVo> files = new ArrayList<>(List.of(
            artifacts.finalizeFile(run, "mp4", finalFile, "video/mp4"),
            artifacts.finalizeFile(run, "contact_sheet", sheet, "image/png"),
            artifacts.finalizeFile(run, "script", write(work.resolve("script.json"), JSON.writeValueAsString(script)),
                "application/json"),
            artifacts.finalizeFile(run, "subtitles", write(work.resolve("subtitles.json"), subtitles), "application/json"),
            artifacts.finalizeFile(run, "checks", write(work.resolve("checks.json"), checks), "application/json"),
            artifacts.finalizeFile(run, "asset_manifest",
                write(work.resolve("asset-manifest.json"), assetManifest(composition, input.path("assets"))),
                "application/json")));
        if (fetched != null) {
            files.add(fetched.artifact());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("script", script);
        // 审稿结论必须随结果落盘：自动发布只认这条记录，内存里通过一次不算数
        result.put("editorialReview", JSON.readTree(review.record));
        if (dataset != null) {
            result.put("dataset", dataset);
        }
        result.put("durationMs", (long) Math.round(audioSeconds * 1000));
        result.put("checks", JSON.readTree(checks));
        // 质量报告与内容属性都是「有就更好」：审稿失败不阻塞出片，属性取不到就不写。
        String qualityReport = qualityReport(run, script, dataset, checks, subtitles);
        if (qualityReport != null) {
            result.put("qualityReport", JSON.readTree(qualityReport));
        }
        String contentProfile = ContentProfileWriter.writeJson(hookType, script);
        if (contentProfile != null) {
            result.put("contentProfile", JSON.readTree(contentProfile));
        }
        return new ProductionResultVo(files, JSON.writeValueAsString(result));
    }

    /**
     * 成片级质量审稿（L3）。
     *
     * <p>L3 不是闸：它失败时这条片子照常完成、照常可发布。审不出来就不写报告，
     * 比写一份半成品更诚实。
     */
    private String qualityReport(ProductionRunVo run, JsonNode script, JsonNode dataset, String checks,
                                 String subtitles) {
        try {
            QualityCriteria.Context ctx = new QualityCriteria.Context(run.versionId(), "final", script, dataset,
                JSON.readTree(checks), subtitles == null ? null : JSON.readTree(subtitles), focusMap(script, dataset));
            return qualityReviewer.review(run, ctx);
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

    private record Gate(String gate, String inputLocator, String reason, Map<String, Object> measured) { }

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

    /** 模板没写 supportedSceneTypes 时返回空集，表示不做类型约束（老模板）。 */
    @SuppressWarnings("unchecked")
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

    /** 审稿发现表达与事实矛盾时定向改一次；改完还不合格就退出，不无限重试。 */
    /**
     * 一次定向修复 + 复审。返回的 record 是最终结论，调用方必须落盘：
     * 自动发布只认这条记录，所以写最终 verdict，不是第一次 FAIL。
     */
    private final class ReviewOutcome {
        String script;
        String record;
        /** 最终稿选中标题的钩子类型；审稿修复换题后必须跟着换，不能沿用旧值。 */
        String hookType;
    }

    private ReviewOutcome reviewScript(ProductionRunVo run, String scriptJson, String basisJson,
                                       DatasetFetchService.Result datasetFetched, boolean dataBoard,
                                       String hookType) {
        EditorialReviewer.Verdict verdict = review(run, scriptJson, basisJson, dataBoard);
        if (!verdict.blocked()) {
            return reviewed(scriptJson, verdict, 0, dataBoard, hookType);
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
            repaired = author.repair(run, scriptJson, verdict.blockingNotes());
        } else {
            DatasetScriptAuthor.Result result =
                datasetAuthor.repair(run, datasetFetched.json(), scriptJson, verdict.blockingNotes());
            repaired = result.script();
            repairedHook = result.hookType();
        }
        EditorialReviewer.Verdict again = review(run, repaired, basisJson, dataBoard);
        if (again.blocked()) {
            throw reviewBlocked(again, "改稿后仍未通过");
        }
        return reviewed(repaired, again, 1, dataBoard, repairedHook);
    }

    private EditorialReviewer.Verdict review(ProductionRunVo run, String scriptJson, String basisJson,
                                             boolean dataBoard) {
        return dataBoard
            ? reviewer.review(run, scriptJson, basisJson)
            : reviewer.reviewEvidence(run, scriptJson, basisJson);
    }

    private ReviewOutcome reviewed(String script, EditorialReviewer.Verdict verdict,
                                   int attempts, boolean dataBoard, String hookType) {
        ReviewOutcome outcome = new ReviewOutcome();
        outcome.script = script;
        outcome.hookType = hookType;
        outcome.record = verdict.toRecord(attempts, dataBoard ? "editorial-review" : "evidence-review",
            LocalDateTime.now(ZoneOffset.UTC));
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
    private static String evidenceQuotes(JsonNode input) {
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

    private Gate renderGate(Path raw) {
        long bytes = size(raw);
        if (bytes <= 0) {
            throw new HeronBusinessException(503, "news_render_failed", "渲染退出 0 但没有成片文件");
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("bytes", bytes);
        measured.put("exitCode", 0);
        return new Gate("render", RAW_FILE, "hyperframes render 退出 0 且产物非空", measured);
    }

    private static Gate decodeGate(JsonNode probe, double audioSeconds) {
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
        if (width != 1080 || height != 1920) {
            problems.add("画面尺寸不是 1080x1920");
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
        return new Gate("decode", RAW_FILE + ":streams", "容器、尺寸、流数与时长实测通过", measured);
    }

    private Gate aigcMetadataGate(ProductionRunVo run, Path finalFile) {
        // 只看 ffmpeg 退出码不够：mp4 不带 use_metadata_tags 时元数据会被静默丢弃，必须读回比对文本。
        ProductionProcessVo probe = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_entries", "format_tags=" + AIGC_TAG, "-of", "default=nw=1:nk=1",
                FINAL_FILE), work(run), PROBE_TIMEOUT));
        if (probe.exitCode() != 0 || !AIGC_STATEMENT.equals(probe.stdout().trim())) {
            throw new HeronBusinessException(409, "news_aigc_metadata_missing", "成片缺少 AIGC 元数据声明，拒绝进入发布");
        }
        return new Gate("aigc_metadata", FINAL_FILE + ":tags", "ffprobe 读回的 AIGC 文本与声明一致",
            Map.of("aigcTag", true));
    }

    private static Gate visualGate(JsonNode check, Path sheet) {
        int errors = 0;
        int warnings = 0;
        // check --json 的每一段是 {ok, errorCount, warningCount, findings:[…]}，计数只认它自己的计数器。
        for (String section : List.of("lint", "runtime", "layout", "contrast", "craft", "audio")) {
            errors += check.path(section).path("errorCount").asInt(0);
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

    private static String gateJson(List<Gate> gates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Gate gate : gates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gate", gate.gate());
            item.put("inputLocator", gate.inputLocator());
            item.put("passed", true);
            item.put("reason", gate.reason());
            item.put("measured", gate.measured());
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

    private JsonNode checkComposition(ProductionRunVo run, Path work) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("node",
            List.of(cliEntry(), "check", COMPOSITION_DIR, "--json"), work, CHECK_TIMEOUT));
        if (result.exitCode() != 0) {
            throw new HeronBusinessException(409, "news_check_failed",
                "版式闸失败：" + safeHead(result.stdout() + " " + result.stderr()));
        }
        return JSON.readTree(result.stdout().substring(result.stdout().indexOf('{')));
    }

    private void render(ProductionRunVo run, Path work) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("node",
            List.of(cliEntry(), "render", COMPOSITION_DIR, "--variables-file", VARS_FILE, "--strict-variables",
                "--quality", "looks", "--workers", "1", "--no-best-effort", "--quiet", "--output", RAW_FILE),
            work, RENDER_TIMEOUT));
        if (result.exitCode() != 0 || size(work.resolve(RAW_FILE)) <= 0) {
            throw new HeronBusinessException(503, "news_render_failed",
                "渲染失败：" + safeTail(result.stdout()) + safeTail(result.stderr()));
        }
    }

    private JsonNode decode(ProductionRunVo run, Path raw) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_format", "-show_streams", "-of", "json", RAW_FILE), work(run), PROBE_TIMEOUT));
        if (result.exitCode() != 0) {
            throw new HeronBusinessException(409, "news_probe_failed", "ffprobe 读取成片失败");
        }
        return JSON.readTree(result.stdout().substring(result.stdout().indexOf('{')));
    }

    /**
     * 成片定稿：混入背景音乐并写 AIGC 元数据，一条命令完成。
     *
     * <p>配乐包络复用字幕对齐后的真实结尾窗口；不以脚本声明时长猜测释放和片尾位置。
     *
     * <p>找不到背景音乐就退化成原样封装，不影响出片：音乐是加分项，不是闸。
     */
    private Path tagAigc(ProductionRunVo run, Path work, NewsNarrationTiming.Span closing) {
        Path bgm = pickBgm();
        List<String> command = new ArrayList<>();
        command.addAll(List.of("-y", "-loglevel", "error", "-i", RAW_FILE));
        boolean withBgm = bgm != null;
        if (withBgm) {
            command.addAll(List.of("-stream_loop", "-1", "-i", bgm.toAbsolutePath().toString()));
            command.addAll(List.of("-filter_complex", bgmFilter(closing)));
            command.addAll(List.of("-map", "0:v:0", "-map", "[mix]"));
            command.addAll(List.of("-c:v", "copy", "-c:a", "aac", "-b:a", "160k"));
        } else {
            command.addAll(List.of("-c", "copy"));
        }
        command.addAll(List.of("-movflags", "+use_metadata_tags",
            "-metadata", AIGC_TAG + "=" + AIGC_STATEMENT, FINAL_FILE));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg", command,
            work, PACKAGE_TIMEOUT));
        if (result.exitCode() != 0 || size(work.resolve(FINAL_FILE)) <= 0) {
            // 混音失败不静默降级成无 BGM：退回去等于掩盖一次真实的编码失败
            throw new HeronBusinessException(503, "news_aigc_metadata_failed", "写入 AIGC 元数据失败");
        }
        return work.resolve(FINAL_FILE);
    }

    /**
     * 正文配乐增益0.22，实际结尾内平滑升至0.40，完整片尾淡出；短结尾缩短两段过渡。
     */
    static String bgmFilter(NewsNarrationTiming.Span closing) {
        if (closing == null || !"closing-slot".equals(closing.id()) || closing.startMs() < 0
            || closing.endMs() <= closing.startMs()) {
            throw new HeronBusinessException(409, "news_audio_alignment", "配乐缺少有效的实际结尾时间");
        }
        double start = closing.startMs() / 1000.0;
        double total = closing.endMs() / 1000.0;
        double halfClosing = (closing.endMs() - closing.startMs()) / 2000.0;
        double rise = Math.min(0.8, halfClosing);
        double fade = Math.min(1.2, halfClosing);
        return "[1:a]atrim=duration=" + fmt(total) + ",asetpts=PTS-STARTPTS,"
            + "volume='if(isnan(t),0.22,0.22+0.18*min(1,max(0,(t-" + fmt(start) + ")/" + fmt(rise)
            + ")))':eval=frame,afade=t=in:st=0:d=" + fmt(Math.min(0.8, total / 2)) + ","
            + "afade=t=out:st=" + fmt(total - fade) + ":d=" + fmt(fade) + "[bg];"
            + "[0:a][bg]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[mix]";
    }

    private static String fmt(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    /**
     * 背景音乐按「最近没用过的优先」轮换，避免每期都是同一首。
     * 目录不存在或没有 mp3 时返回 null，由调用方退化成无 BGM。
     *
     * <p>BGM 是随仓库分发的资产，不跟 dataRoot 走：dataRoot 是工作目录（每个任务一个临时目录），
     * 在那里找音乐永远找不到。听感资产和产出物不是同一类东西。
     */
    private Path pickBgm() {
        Path root = properties.getNewsTemplate();
        Path audio = root == null ? null
            : root.toAbsolutePath().normalize().getParent().resolve("audio").resolve("bgm");
        if (audio == null || !java.nio.file.Files.isDirectory(audio)) {
            // 部署侧可能把模板目录指到别处；退回仓库默认布局再试一次
            audio = java.nio.file.Path.of("data", "audio", "bgm").toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(audio)) {
                return null;
            }
        }
        try (var files = java.nio.file.Files.list(audio)) {
            List<Path> tracks = files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp3"))
                .sorted().toList();
            if (tracks.isEmpty()) {
                return null;
            }
            // shortcut: 按日轮换，不做「哪首用过」的持久账本；要做效果归因时再按作品表现挑
            int index = Math.floorMod(java.time.LocalDate.now().getDayOfYear(), tracks.size());
            return tracks.get(index);
        } catch (java.io.IOException e) {
            log.warn("背景音乐目录不可读，本期无 BGM: {}", e.getMessage());
            return null;
        }
    }

    private Path contactSheet(ProductionRunVo run, Path work, Path finalFile) {
        int step = Math.max(1, (int) Math.floor(probeDuration(run, finalFile) / SHEET_TILES));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg",
            List.of("-y", "-loglevel", "error", "-i", FINAL_FILE, "-vf",
                "fps=1/" + step + ",scale=360:640,tile=3x2", "-frames:v", "1", SHEET_FILE), work, PACKAGE_TIMEOUT));
        if (result.exitCode() != 0 || size(work.resolve(SHEET_FILE)) <= 0) {
            throw new HeronBusinessException(503, "news_contact_sheet_failed", "联络表生成失败");
        }
        return work.resolve(SHEET_FILE);
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
    private Map<String, String> materializeImages(java.nio.file.Path work, JsonNode script, JsonNode input) {
        Map<String, String> resolved = new TreeMap<>();
        Map<String, String> byAsset = indexEvidenceAssets(input);
        JsonNode scenes = script.path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            JsonNode assets = scenes.get(index).path("visual").path("assets");
            if (!assets.isArray() || assets.isEmpty()) {
                continue;
            }
            JsonNode first = assets.get(0);
            String url = first.path("source_url").asString("");
            if (url.isBlank()) {
                url = byAsset.getOrDefault(first.path("asset_id").asString(""), "");
            }
            if (url.isBlank()) {
                continue;
            }
            try {
                var bytes = fetcher.fetchImage(java.net.URI.create(url));
                if (bytes.isEmpty()) {
                    continue;
                }
                String name = "scene" + (index + 1) + imageExt(url);
                java.nio.file.Files.write(work.resolve(name), bytes.get());
                resolved.put(String.valueOf(index + 1), name);
            } catch (RuntimeException | java.io.IOException e) {
                log.warn("场景 {} 的配图取不到，退化成纯文字: {}", index + 1, e.getMessage());
            }
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
        ObjectNode vars = (ObjectNode) JSON.readTree(templateVariables(DatasetPlaceholders.resolve(script, dataset),
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
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("locked", JSON.readTree(JSON.writeValueAsString(locked)));
        manifest.put("files", fileHashes(composition));
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
        BufferedImage image;
        try {
            image = ImageIO.read(sheet.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (image == null) {
            throw new HeronBusinessException(409, "news_contact_sheet_unreadable", "联络表不可读");
        }
        int columns = 3;
        int rows = 2;
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
