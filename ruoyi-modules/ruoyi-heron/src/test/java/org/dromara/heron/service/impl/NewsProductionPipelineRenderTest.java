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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真工具跑一遍渲染半程（假稿件 → edge-tts → hyperframes check/render → ffprobe → AIGC 元数据 → 联络表 → 六类产物）。
 * 单独打 tag：它要几分钟真渲染，默认构建不跑，也不依赖数据库与模型。
 */
@Tag("render")
@DisplayName("新闻流水线真渲染：十道闸与六类产物全部来自真实命令")
class NewsProductionPipelineRenderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCRIPT = """
        {"source_url":"https://example.com/tech/release","fetched_at":"%s",
         "body_sha256":"%s",
         "title":"新款折叠屏手机上市",
         "scenes":[
           {"lead":"铰链寿命提到四十万次","body":"厂商在今日的发布会上公布新一代折叠机型，主打铰链与整机耐用度。官方资料显示，铰链开合寿命较上一代提升约一倍，屏幕折痕在侧视角度下明显变浅。","duration_s":12},
           {"lead":"起售价与上一代持平","body":"价格方面，起步版本不做上调，存储升级需要加价。渠道反馈首销期以旧换新补贴是主要促销手段，线下门店同步开启真机体验。","duration_s":12},
           {"lead":"下周开启全渠道现货","body":"官方宣布下周一开启全渠道现货发售，首批覆盖国内主要城市，海外上市时间表尚未公布。行业机构预计本季度折叠屏整体出货量小幅回升。","duration_s":12}],
         "closing":"我们会继续跟进真机实测与售后条款变化。",
         "source_name":"公开报道整理"}
        """.formatted(OffsetDateTime.now(ZoneOffset.UTC).toString(),
            DigestUtil.sha256Hex("foldable-phone-article"));

    @Test
    @DisplayName("六类产物齐备，十道闸全部由真实命令判定通过")
    void rendersOneRealVideo() {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path templateRoot = repository.resolve("data").resolve("templates");
        Path template = templateRoot.resolve("news-9x16");
        Path cli = repository.resolve("data").resolve("tools").resolve("node_modules")
            .resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);
        assertTrue(Files.isRegularFile(cli), "缺少 hyperframes 入口：" + cli);

        // 真渲染会让 Chrome 短暂持有目录句柄，@TempDir 在 Windows 上删不掉；落进被忽略的 data/it-render 还留下可看的成片。
        Path dataRoot = repository.resolve("data").resolve("it-render").resolve(UUID.randomUUID().toString());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setNewsTemplate(templateRoot);
        properties.setHyperframes(cli.toString());
        properties.setGuardEnabled(false);
        // PATH 上的 node 可能被同机其它会话切成 20.x，而 hyperframes 要求 >=22：与部署侧一样用绝对路径钉住。
        properties.setNode(System.getenv().getOrDefault("HERON_TEST_NODE", "node"));
        NewsProductionPipeline pipeline = new NewsProductionPipeline(
            fixedAuthor(SCRIPT), new ProductionProcessService(properties,
            new ProductionArtifactService(properties)), new ProductionArtifactService(properties), properties,
            (run, stage, progress) -> true, null, null, null, passingReview(),
            new NewsSourceFetcher(null, null, new HeronNewsProperties()));

        ProductionRunVo run = new ProductionRunVo(7001L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), lockedAssets(template), null);
        ProductionResultVo result = pipeline.execute(run);

        JsonNode content = JSON.readTree(result.resultJson());
        assertEquals(content.path("script").toString(), JSON.readTree(SCRIPT).toString(), "入库稿件必须等于写稿结果");
        assertTrue(content.path("durationMs").asLong() >= 30_000 && content.path("durationMs").asLong() <= 90_000,
            "实测时长应落在契约区间：" + content.path("durationMs").asLong());
        List<String> kinds = new ArrayList<>();
        for (ProductionArtifactVo artifact : result.artifacts()) {
            kinds.add(artifact.kind());
            assertTrue(artifact.bytes() > 0, artifact.kind() + " 是空产物");
        }
        assertEquals(List.of("asset_manifest", "checks", "contact_sheet", "mp4", "script", "subtitles"),
            kinds.stream().sorted().toList());

        JsonNode checks = content.path("checks");
        assertEquals(10, checks.size(), checks.toString());
        for (JsonNode gate : checks) {
            assertTrue(gate.path("passed").asBoolean(), gate.path("gate").asString("") + " 未通过");
            assertTrue(gate.path("measured").size() > 0, gate.path("gate").asString("") + " 没有实测值");
        }
        for (JsonNode gate : checks) {
            String locator = gate.path("inputLocator").asString("");
            String reason = gate.path("reason").asString("");
            // 生产域禁这两个字段带路径分隔符；在这里挡住，别让任务白跑两分钟。
            assertFalse(locator.contains("/") || locator.contains("\\"), gate + " 的定位符带路径");
            assertFalse(reason.contains("/") || reason.contains("\\"), gate + " 的理由带路径");
        }
        JsonNode visual = find(checks, "visual");
        assertEquals(0, visual.path("measured").path("blankFrames").asInt(), "联络表出现空白帧");
        assertTrue(visual.path("measured").path("checkErrors").asInt() == 0, "版式闸有 error");
        assertTrue(find(checks, "audio").path("measured").path("voiceBytes").asLong() > 1000);
        assertTrue(find(checks, "assets").path("measured").path("manifestMatched").asBoolean());
    }

    /**
     * 图片场景要真渲一遍：模板的 .scene-figure 是否展开、图有没有真进画面，
     * 靠读代码看不出来——CSS 的 display 与 has-image 类是两处协同。
     *
     * <p>图片用本机临时文件而不是外网 URL：这条测试不该依赖网络可达。
     */
    @Test
    @DisplayName("图片场景：配图落到工作目录并进入渲染，联络表没有空白帧")
    void rendersImageScene() throws Exception {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path templateRoot = repository.resolve("data").resolve("templates");
        Path template = templateRoot.resolve("news-9x16");
        Path cli = repository.resolve("data").resolve("tools").resolve("node_modules")
            .resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);

        Path dataRoot = repository.resolve("data").resolve("it-render").resolve(UUID.randomUUID().toString());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setNewsTemplate(templateRoot);
        properties.setHyperframes(cli.toString());
        properties.setGuardEnabled(false);
        properties.setNode(System.getenv().getOrDefault("HERON_TEST_NODE", "node"));

        // 稿子声明了一个 image 场景；图片地址由替身 fetcher 返回，不真的出网
        String script = SCRIPT.replace(
            "\"scenes\":[",
            "\"scenes\":[{\"lead\":\"配图场景验证\",\"body\":\"这一段应当带一张配图。\",\"duration_s\":10,"
                + "\"scene_type\":\"image\",\"visual\":{\"layout\":\"image_with_context\","
                + "\"assets\":[{\"asset_id\":\"img_0\",\"source_url\":\"https://example.com/photo.jpg\"}]}},");

        NewsSourceFetcher stubFetcher = new NewsSourceFetcher(null, null, new HeronNewsProperties()) {
            @Override
            public java.util.Optional<byte[]> fetchImage(java.net.URI url) {
                return java.util.Optional.of(tinyPng());
            }
        };
        NewsProductionPipeline pipeline = new NewsProductionPipeline(
            fixedAuthor(script), new ProductionProcessService(properties,
            new ProductionArtifactService(properties)), new ProductionArtifactService(properties), properties,
            (run, stage, progress) -> true, null, null, null, passingReview(), stubFetcher);

        ProductionRunVo run = new ProductionRunVo(7002L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), lockedAssets(template), null);
        ProductionResultVo result = pipeline.execute(run);

        // 配图必须真的落到工作目录并被模板引用
        Path varsFile = findFile(dataRoot, "vars.json");
        assertTrue(varsFile != null, "应当生成 vars.json");
        String varsJson = Files.readString(varsFile);
        assertTrue(varsJson.contains("scene1_image"), "vars 里应有 scene1_image：" + varsJson);
        assertFalse(varsJson.contains("scene1_image\": \"\""), "scene1_image 不该是空串");
        assertTrue(findFile(dataRoot, "scene1.png") != null || findFile(dataRoot, "scene1.jpg") != null,
            "配图文件应当已经下载到工作目录");

        JsonNode content = JSON.readTree(result.resultJson());
        JsonNode visual = find(content.path("checks"), "visual");
        assertEquals(0, visual.path("measured").path("blankFrames").asInt(), "带图场景不该出现空白帧");
    }

    /** 1×1 PNG：足够让模板的 img 有内容，又不让测试体积变大。 */
    private static byte[] tinyPng() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    /** 产物目录层级由生产域决定，这里按文件名递归找，避免把布局写死在测试里。 */
    private static Path findFile(Path root, String name) throws Exception {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals(name))
                .findFirst().orElse(null);
        }
    }

    private static JsonNode find(JsonNode checks, String gate) {        for (JsonNode item : checks) {
            if (gate.equals(item.path("gate").asString(""))) {
                return item;
            }
        }
        throw new AssertionError("缺少闸 " + gate);
    }

    /** 认领时锁定的资产快照：模板目录名与实算哈希，与部署侧要写的值同一算法。字体十二套共用，取模板根目录同级的 fonts/。 */
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

    private static NewsScriptAuthor fixedAuthor(String script) {
        return new NewsScriptAuthor() {
            @Override
            public String author(ProductionRunVo run) {
                return script;
            }

            @Override
            public String repair(ProductionRunVo run, String scriptJson, List<String> notes) {
                return script;
            }
        };
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
