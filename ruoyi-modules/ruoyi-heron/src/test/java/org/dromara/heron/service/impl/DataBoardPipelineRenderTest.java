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
import java.nio.file.StandardCopyOption;
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
        + "{\"kind\":\"数字反差\",\"title\":\"金价创阶段新高，还能追吗\"},"
        + "{\"kind\":\"疑问悬念\",\"title\":\"这波还能涨多久\"},"
        + "{\"kind\":\"利益相关\",\"title\":\"手里有黄金的看过来\"},"
        + "{\"kind\":\"时间紧迫\",\"title\":\"这周只看一个位置\"},"
        + "{\"kind\":\"反常识\",\"title\":\"涨得动吗\"}]}";

    private static final String SCRIPT = """
        {"title":"金价创阶段新高，还能追吗",
         "scenes":[
           {"lead":"金价站上关键位","body":"最新收于 {{facts.last.value}} 美元。","duration_s":12},
           {"lead":"较前一日走高","body":"较前一日上涨 {{facts.change}} 美元。","duration_s":12},
           {"lead":"区间高点确认","body":"区间最高 {{facts.max.value}} 美元。","duration_s":12},
           {"lead":"后续关注两点","body":"接下来关注美元走势与下一批经济数据。","duration_s":12},
           {"lead":"市场情绪回暖","body":"资金面显示避险需求仍在，交易量较上周放大。","duration_s":12},
           {"lead":"数据口径说明","body":"以上为收盘价，区间取最近两周交易日。","duration_s":12}],
         "closing":"以上数据来自公开行情，仅供参考，不构成任何投资建议。",
         "source_name":"模型自述"}
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
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setNewsTemplate(templateRoot);
        properties.setHyperframes(cli.toString());
        properties.setGuardEnabled(false);
        properties.setNode(System.getenv().getOrDefault("HERON_TEST_NODE", "node"));

        String datasetJson = SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD,
            DATASET_BODY, Instant.now(), 30);
        ProductionArtifactVo datasetArtifact = new ProductionArtifactVo(UUID.randomUUID().toString(), "dataset",
            "dataset.json", datasetJson.getBytes(StandardCharsets.UTF_8).length,
            DigestUtil.sha256Hex(datasetJson), "application/json");

        DatasetRouter router = new DatasetRouter(null, null, null) {
            @Override
            public Route route(ProductionRunVo run, JsonNode input) {
                return new Route("sina-gold-xau", false);
            }
        };
        DatasetFetchService fetch = new DatasetFetchService(null, null) {
            @Override
            public Result fetch(ProductionRunVo run, String sourceKey, int limit) {
                return new Result(datasetJson, DigestUtil.sha256Hex(datasetJson), datasetArtifact);
            }
        };
        DatasetScriptAuthor author = new DatasetScriptAuthor(new OpenAiModelClient(null, null, null) {
            @Override
            public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
                return "dataset-title".equals(stage) ? TITLES : SCRIPT;
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
            new ProductionProcessService(properties, new ProductionArtifactService(properties)),
            new ProductionArtifactService(properties), properties, (run, stage, progress) -> true,
            router, fetch, author, passingReview(), new NewsSourceFetcher(null, null, new HeronNewsProperties()));

        ObjectNode input = (ObjectNode) JSON.readTree(lockedAssets(template));
        input.put("kind", "data_board");
        input.put("title", "金价创阶段新高");
        input.put("draft", "金价连续走高，创下阶段新高。");
        ProductionRunVo run = new ProductionRunVo(7001L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), JSON.writeValueAsString(input), null);
        ProductionResultVo result = pipeline.execute(run);

        JsonNode content = JSON.readTree(result.resultJson());
        assertTrue(content.path("durationMs").asLong() >= 30_000, "时长不足");
        List<String> kinds = new ArrayList<>();
        for (ProductionArtifactVo artifact : result.artifacts()) {
            kinds.add(artifact.kind());
            assertTrue(artifact.bytes() > 0, artifact.kind() + " 是空产物");
        }
        assertEquals(List.of("asset_manifest", "checks", "contact_sheet", "dataset", "mp4", "script", "subtitles"),
            kinds.stream().sorted().toList());
        JsonNode checks = content.path("checks");
        assertEquals(11, checks.size(), checks.toString());
        for (JsonNode gate : checks) {
            assertTrue(gate.path("passed").asBoolean(), gate.path("gate").asString("") + " 未通过");
        }

        Path mp4 = dataRoot.resolve(byKind(result, "mp4").storageKey());
        Path sheet = dataRoot.resolve(byKind(result, "contact_sheet").storageKey());
        Path preview = repository.resolve("data").resolve("it-render").resolve("data-board-preview.mp4");
        Path previewSheet = repository.resolve("data").resolve("it-render").resolve("data-board-preview.png");
        Files.createDirectories(preview.getParent());
        Files.copy(mp4, preview, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sheet, previewSheet, StandardCopyOption.REPLACE_EXISTING);
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
                return new EditorialReviewer.Verdict("PASS", List.of(), List.of());
            }

            @Override
            public EditorialReviewer.Verdict reviewEvidence(ProductionRunVo run, String scriptJson, String evidenceJson) {
                return new EditorialReviewer.Verdict("PASS", List.of(), List.of());
            }
        };
    }
}
