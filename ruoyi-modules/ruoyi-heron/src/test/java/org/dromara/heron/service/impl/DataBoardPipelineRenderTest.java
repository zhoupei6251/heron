package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据看板真渲染：固定数据快照 → 固定脚本 → edge-tts → check/render → ffprobe → AIGC → 七类产物。
 * 单独打 tag，默认构建不跑。
 */
@Tag("render")
@DisplayName("数据看板真渲染：七类产物与十一道闸")
class DataBoardPipelineRenderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DATASET_BODY = "/*x*/ var _hf_XAU=(["
        + "{\"date\":\"2026-11-05\",\"close\":\"4091.55\"},"
        + "{\"date\":\"2026-11-06\",\"close\":\"4105.50\"},"
        + "{\"date\":\"2026-11-09\",\"close\":\"4110.04\"},"
        + "{\"date\":\"2026-11-10\",\"close\":\"4113.34\"}]);";
    private static final String TITLES = "{\"candidates\":["
        + "{\"kind\":\"数字反差\",\"title\":\"四期金价区间变化\"},"
        + "{\"kind\":\"疑问悬念\",\"title\":\"四期价格怎么变化\"},"
        + "{\"kind\":\"利益相关\",\"title\":\"看懂这份行情快照\"},"
        + "{\"kind\":\"时间紧迫\",\"title\":\"先看清统计区间\"},"
        + "{\"kind\":\"反常识\",\"title\":\"区间涨跌不是单日涨跌\"}]}";

    private static final String PLAN = """
        {"hookType":"数字反差","coreThesis":"四期区间变化不等于单日涨跌","scenes":[
          {"purpose":"给出末值","evidenceRefs":["facts.last.value","facts.last.date"],"beat":"HOOK","emotion":"好奇","rhythm":"紧凑",
           "expectedVisual":{"sceneType":"headline","description":"末值与日期"}},
          {"purpose":"解释区间变化","evidenceRefs":["facts.change","facts.change_pct"],"beat":"EVIDENCE","emotion":"平静","rhythm":"平稳",
           "expectedVisual":{"sceneType":"chart","description":"首末变化"}},
          {"purpose":"交代高低点","evidenceRefs":["facts.max.value","facts.min.value"],"beat":"CONCLUSION","emotion":"释然","rhythm":"舒缓",
           "expectedVisual":{"sceneType":"number","description":"高低点数字"}}]}
        """;

    static final String SCRIPT = """
        {"title":"四期金价区间变化",
         "scenes":[
           {"lead":"先看最新一期","body":"演示快照最新收于 {{facts.last.value}} 美元，对应 {{facts.last.date}}。","duration_s":12,
            "scene_type":"headline","beat":"HOOK","on_screen_text":"最新一期"},
           {"lead":"涨幅先别看错","body":"区间增加 {{facts.change}} 美元，涨幅 {{facts.change_pct}}%。这描述首末差异。","duration_s":12,
            "scene_type":"chart","beat":"EVIDENCE"},
           {"lead":"高低点在哪里","body":"区间最高 {{facts.max.value}} 美元，最低 {{facts.min.value}} 美元。","duration_s":12,
            "scene_type":"number","beat":"CONCLUSION"}],
         "closing":"这份固定快照只有四期收盘价，区间变化不代表单日涨跌，也不能预测下一期；本片用于验收配音、分镜与图表同步。",
         "source_name":"示例快照"}
        """;

    @Test
    @DisplayName("一条数据看板从快照渲染到成片，产物与闸全部来自真实命令")
    void rendersOneDataBoard() throws Exception {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path templateRoot = repository.resolve("data").resolve("templates");
        Path template = templateRoot.resolve("news-data-9x16");
        Path cli = repository.resolve("data").resolve("tools").resolve("node_modules")
            .resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);
        assertTrue(Files.isRegularFile(cli), "缺少 hyperframes 入口：" + cli);

        Path dataRoot = repository.resolve("data").resolve("it-render").resolve(UUID.randomUUID().toString());
        System.out.println("DATA_ROOT=" + dataRoot.toAbsolutePath());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setNewsTemplate(templateRoot);
        properties.setHyperframes(cli.toString());
        properties.setGuardEnabled(false);
        properties.setNode(System.getenv().getOrDefault("HERON_TEST_NODE", "node"));

        ObjectNode dataset = (ObjectNode) JSON.readTree(SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD,
            DATASET_BODY, Instant.now(), 30));
        dataset.put("source_name", "示例快照 · 功能验收");
        String datasetJson = JSON.writeValueAsString(dataset);
        Files.createDirectories(dataRoot);
        ProductionArtifactService artifactService = new ProductionArtifactService(properties, null);

        DatasetRouter router = new DatasetRouter(null, null, null) {
            @Override
            public Route route(ProductionRunVo run, JsonNode input) {
                return new Route("sina-gold-xau", false);
            }
        };
        DatasetFetchService fetch = new DatasetFetchService(null, null) {
            @Override
            public Result fetch(ProductionRunVo run, String sourceKey, int limit) {
                // 生产链路把数据集写进本次尝试目录再定稿；测试夹具必须走同一路径，否则定稿会拒绝外部文件
                Path datasetFile = artifactService.prepareWorkDirectory(run).resolve("dataset.json");
                try {
                    Files.writeString(datasetFile, datasetJson, StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                return new Result(datasetJson, DigestUtil.sha256Hex(datasetJson),
                    artifactService.finalizeFile(run, "dataset", datasetFile, "application/json"));
            }
        };
        DatasetScriptAuthor author = new DatasetScriptAuthor(new OpenAiModelClient(null, null, null) {
            @Override
            public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
                return "dataset-title".equals(stage) ? TITLES
                    : stage.startsWith("dataset-story-plan") ? PLAN : SCRIPT;
            }
        }, new DatasetTitleAuthor(new OpenAiModelClient(null, null, null) {
            @Override
            public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
                return TITLES;
            }
        }));

        NewsProductionPipeline pipeline = new NewsProductionPipeline(
            new NewsScriptAuthor() {
                @Override
                public String author(ProductionRunVo run) {
                    return "{}";
                }

                @Override
                public String repair(ProductionRunVo run, String scriptJson, List<String> notes) {
                    return scriptJson;
                }
            },
            null,
            new ProductionProcessService(properties, artifactService),
            artifactService, properties, (run, stage, progress) -> true,
            router, fetch, author, passingReview(), null, new NewsSourceFetcher(null, null, new HeronNewsProperties()),
            org.mockito.Mockito.mock(FrozenContextService.class));

        ObjectNode input = (ObjectNode) JSON.readTree(lockedAssets(template));
        input.put("kind", "data_board");
        input.put("title", "四期金价区间变化");
        input.put("draft", "固定示例快照的区间比较与统计口径。");
        ProductionRunVo run = new ProductionRunVo(7001L, ProductionBizType.news_explainer, 10L, 5L, 1,
            UUID.randomUUID().toString(), JSON.writeValueAsString(input));
        ProductionResultVo result = pipeline.execute(run);

        JsonNode content = JSON.readTree(result.resultJson());
        assertTrue(content.path("durationMs").asLong() >= 30_000, "时长不足");
        List<String> kinds = new ArrayList<>();
        for (ProductionArtifactVo artifact : result.artifacts()) {
            kinds.add(artifact.kind());
            assertTrue(artifact.bytes() > 0, artifact.kind() + " 是空产物");
            Path file = dataRoot.resolve(artifact.storageKey());
            assertTrue(Files.isRegularFile(file), artifact.kind() + " 未实际落盘");
            assertEquals(artifact.sha256(), DigestUtil.sha256Hex(file.toFile()), artifact.kind() + " 指纹不匹配");
        }
        assertEquals(List.of("asset_manifest", "checks", "contact_sheet", "dataset", "mp4", "script", "subtitles"),
            kinds.stream().sorted().toList());
        JsonNode checks = content.path("checks");
        // 断闸名而不是个数：多一道少一道都要看得见
        java.util.Set<String> gateNames = new java.util.HashSet<>();
        for (JsonNode gate : checks) {
            gateNames.add(gate.path("gate").asString(""));
            assertTrue(gate.path("passed").asBoolean(), gate.path("gate").asString("") + " 未通过");
        }
        assertEquals(java.util.Set.of("schema", "evidence", "data", "audio", "audio_mix", "subtitles", "render",
            "decode", "visual", "aigc_metadata", "aigc_visible", "assets", "scene_type", "sfx"), gateNames, checks.toString());

        Path mp4 = dataRoot.resolve(byKind(result, "mp4").storageKey());
        Path sheet = dataRoot.resolve(byKind(result, "contact_sheet").storageKey());
        Path preview = dataRoot.resolve("data-board-preview.mp4");
        Path previewSheet = dataRoot.resolve("data-board-preview.png");
        Files.createDirectories(preview.getParent());
        Files.copy(mp4, preview);
        Files.copy(sheet, previewSheet);
        Files.writeString(dataRoot.resolve("result.json"), result.resultJson(), StandardCharsets.UTF_8);
        System.out.println("MP4=" + preview.toAbsolutePath());
        System.out.println("SHEET=" + previewSheet.toAbsolutePath());
        System.out.println("DURATION_MS=" + content.path("durationMs").asLong());
    }

    private static ProductionArtifactVo byKind(ProductionResultVo result, String kind) {
        for (ProductionArtifactVo artifact : result.artifacts()) {
            if (kind.equals(artifact.kind())) return artifact;
        }
        throw new AssertionError("缺少产物 " + kind);
    }

    private static String lockedAssets(Path template) {
        Map<String, Object> assets = new TreeMap<>();
        assets.put("templateKey", template.getFileName().toString());
        assets.put("templateVersion", "1");
        assets.put("fontSha256", DigestUtil.sha256Hex(
            template.getParent().getParent().resolve("fonts").resolve("NotoSansSC-Regular.otf").toFile()));
        assets.put("voiceKey", "zh-CN-XiaoxiaoNeural");
        assets.put("voiceVersion", "1");
        assets.put("assetManifestSha256", DigestUtil.sha256Hex(
            template.resolve("assets-manifest.json").toFile()));
        return JSON.writeValueAsString(Map.of("assets", assets));
    }

    /** 审稿不是这些用例的被测对象，一律放行。 */
    private static EditorialReviewer passingReview() {
        return new EditorialReviewer(null) {
            @Override
            public EditorialReviewer.Verdict review(ProductionRunVo run, String scriptJson, String factsJson) {
                return new EditorialReviewer.Verdict("PASS", null, List.of(), List.of());
            }

            @Override
            public EditorialReviewer.Verdict reviewEvidence(ProductionRunVo run, String scriptJson, String evidenceJson) {
                return new EditorialReviewer.Verdict("PASS", null, List.of(), List.of());
            }
        };
    }
}
