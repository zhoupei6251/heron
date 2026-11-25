package org.dromara.heron.service.impl;

import cn.hutool.core.lang.Dict;
import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.system.api.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识解释线真链路：真知识包、真模型写稿、真渲染。
 *
 * <p>只打 render 标签，默认构建不跑；缺少模型凭据时直接失败，不静默改用固定稿。
 */
@Tag("render")
@DisplayName("知识解释线真链路：真知识包与真模型")
class KnowledgeRealChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 一条能回答「第一次租房怎么少亏钱」的跨章主题。 */
    private static final List<String> ENTRIES = List.of("15-01", "15-03", "05-03");
    private static final String TEMPLATE = "knowledge-mono-9x16";

    @Test
    @DisplayName("一条真知识视频：写稿、引用闸、渲染、产物七类齐备")
    void runsRealChain() throws Exception {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path templateRoot = repository.resolve("data").resolve("templates");
        Path template = templateRoot.resolve(TEMPLATE);
        Path cli = repository.resolve("data").resolve("tools").resolve("node_modules")
            .resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);
        assertTrue(Files.isRegularFile(cli), "缺少 hyperframes 入口：" + cli);

        Path dataRoot = repository.resolve("data").resolve("it-render").resolve(UUID.randomUUID().toString());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        properties.setKnowledgeRoot(repository.resolve("data").resolve("knowledge"));
        properties.setNewsTemplate(templateRoot);
        properties.setHyperframes(cli.toString());
        properties.setGuardEnabled(false);
        properties.setNode(System.getenv().getOrDefault("HERON_TEST_NODE", "node"));

        String apiKey = System.getenv("HERON_REAL_MODEL_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 HERON_REAL_MODEL_KEY，真链路不静默改用固定稿");
        }
        String baseUrl = System.getenv().getOrDefault("HERON_REAL_MODEL_BASE",
            "https://ark.cn-beijing.volces.com/api/coding/v3");
        String modelName = System.getenv().getOrDefault("HERON_REAL_MODEL", "deepseek-v4.1-flash-economy");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("model", Map.of(
            "heron.model.base-url", baseUrl,
            "heron.model.name", modelName,
            "heron.model.api-key", apiKey)));
        ConfigService config = new ConfigService() {
            @Override public String getConfigValue(String key) { return null; }
            @Override public Dict getConfigMap(String key) { return null; }
            @Override public List<Dict> getConfigArrayMap(String key) { return List.of(); }
            @Override public <T> T getConfigObject(String key, Class<T> clazz) { return null; }
            @Override public <T> List<T> getConfigArray(String key, Class<T> clazz) { return List.of(); }
        };
        OpenAiModelClient model = new OpenAiModelClient(config, environment, null) {
            @Override
            public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
                String reply = chatWithoutUsage(systemPrompt, userPrompt);
                try {
                    Path out = Path.of("..", "..", "data", "it-render", "real-model-knowledge-" + stage + ".txt")
                        .toAbsolutePath().normalize();
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, reply, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // 调试留档失败不影响主链路
                }
                return reply;
            }
        };

        KnowledgeScriptAuthor knowledgeAuthor = new KnowledgeScriptAuthor(model, properties);
        NewsProductionPipeline pipeline = new NewsProductionPipeline(
            unusedAuthor(), knowledgeAuthor,
            new ProductionProcessService(properties, new ProductionArtifactService(properties, null)),
            new ProductionArtifactService(properties, null), properties, (run, stage, progress) -> true,
            null, null, null, new EditorialReviewer(model), null,
            new NewsSourceFetcher(null, null, new HeronNewsProperties()),
            org.mockito.Mockito.mock(FrozenContextService.class));

        ObjectNode input = (ObjectNode) JSON.readTree(lockedAssets(template));
        input.put("kind", "knowledge_explainer");
        input.put("title", "第一次租房先做这三件事");
        input.put("draft", "把押金条款、中介代收和公积金提取三件事讲清楚，帮第一次租房的人少亏钱。");
        ObjectNode editorial = input.putObject("editorial");
        editorial.put("editorialIntent", "第一次租房的人最容易被押金和中介坑，按先合同、再付款、后补贴的顺序讲");
        editorial.put("reason", "知识线备用槽位：用三条可执行的建议回答一个具体问题");
        ObjectNode selection = input.putObject("selection");
        selection.put("candidateId", 1L);
        selection.put("dataSourceKey", "how-to-live-better");
        selection.put("digest", false);
        selection.put("templateId", TEMPLATE);
        selection.put("storyAngle", "");
        tools.jackson.databind.node.ArrayNode ids = selection.putArray("sourceEvidenceIds");
        ENTRIES.forEach(ids::add);

        ProductionRunVo run = new ProductionRunVo(7001L, ProductionBizType.news_explainer, 10L, 5L, 1,
            UUID.randomUUID().toString(), JSON.writeValueAsString(input));
        ProductionResultVo result = pipeline.execute(run);

        JsonNode content = JSON.readTree(result.resultJson());
        assertTrue(content.path("durationMs").asLong() >= 30_000, "时长不足");
        List<String> kinds = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (ProductionArtifactVo artifact : result.artifacts()) {
            kinds.add(artifact.kind());
            Path file = dataRoot.resolve(artifact.storageKey());
            assertTrue(Files.isRegularFile(file), artifact.kind() + " 未实际落盘");
            assertEquals(artifact.sha256(), DigestUtil.sha256Hex(file.toFile()), artifact.kind() + " 指纹不匹配");
            files.add(file);
        }
        assertEquals(List.of("asset_manifest", "checks", "citations", "contact_sheet", "mp4", "script", "subtitles"),
            kinds.stream().sorted().toList());

        JsonNode checks = content.path("checks");
        java.util.Set<String> gateNames = new java.util.HashSet<>();
        for (JsonNode gate : checks) {
            gateNames.add(gate.path("gate").asString(""));
            assertTrue(gate.path("passed").asBoolean(), gate.path("gate").asString("") + " 未通过");
        }
        assertEquals(java.util.Set.of("schema", "evidence", "citation", "audio", "audio_mix", "subtitles",
            "render", "decode", "visual", "aigc_metadata", "aigc_visible", "assets", "scene_type", "storyboard",
            "video_signal"), gateNames, checks.toString());

        // 稿件必须真的引用了这三条，片尾必须带署名原文
        JsonNode script = content.path("script");
        JsonNode reviewRecord = content.path("editorialReview");
        assertEquals("knowledge-review", reviewRecord.path("promptVersion").asString(),
            "知识线审稿必须落自己的口径，自动发布才知道要不要 coreFacts");
        assertEquals("PASS", reviewRecord.path("result").asString());
        for (JsonNode scene : script.path("scenes")) {
            assertFalse(scene.path("entry_ids").isEmpty(), "每段都该挂 entryId");
        }
        assertTrue(script.path("closing").asString("").contains("CC BY 4.0"), "片尾缺少署名");
        KnowledgePack pack = KnowledgePack.load(repository.resolve("data").resolve("knowledge"), "how-to-live-better");
        assertEquals(pack.originUrl(), script.path("source_url").asString());
        assertEquals(pack.digest(), script.path("body_sha256").asString());

        Path mp4 = files.stream().filter(path -> path.toString().endsWith(".mp4")).findFirst().orElseThrow();
        System.out.println("KNOWLEDGE_VIDEO=" + mp4);
        System.out.println("SCRIPT=" + script.toString());
    }

    private static NewsScriptAuthor unusedAuthor() {
        return new NewsScriptAuthor() {
            @Override public String author(ProductionRunVo run) { return "{}"; }
            @Override public String repair(ProductionRunVo run, String scriptJson, List<String> notes) {
                return scriptJson;
            }
        };
    }

    /** 认领时锁定的资产快照：与部署侧同一算法。字体共用，取模板根目录同级的 fonts/。 */
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
}
