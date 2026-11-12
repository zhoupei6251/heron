package org.dromara.heron.service.impl;

import cn.hutool.core.lang.Dict;
import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronDatasetUsageMapper;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.system.api.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真数据 + 真模型 + 真渲染：新浪金价快照 → 本地 Ollama 写稿 → edge-tts → hyperframes → ffmpeg。
 * 只打 tag，默认构建不跑；本机没有 Ollama 或模型时直接失败，不静默改用固定稿。
 */
@Tag("render")
@DisplayName("数据看板真链路：真数据与真模型")
class DataBoardRealChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("一条真数据看板：抓取、写稿、渲染全走真实外部效果")
    void runsRealChain() throws Exception {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        Path templateRoot = repository.resolve("data").resolve("templates");
        Path template = templateRoot.resolve("news-data-9x16");
        Path cli = repository.resolve("data").resolve("tools").resolve("node_modules")
            .resolve("hyperframes").resolve("bin").resolve("hyperframes.mjs");
        assertTrue(Files.isDirectory(template), "缺少模板目录：" + template);
        assertTrue(Files.isRegularFile(cli), "缺少 hyperframes 入口：" + cli);

        List<HeronDataSource> registry = registry(repository);
        assertTrue(registry.size() > 50, "注册表读取失败，只解析到 " + registry.size() + " 个源");

        Path dataRoot = repository.resolve("data").resolve("it-render").resolve(UUID.randomUUID().toString());
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(dataRoot);
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
                    Path out = Path.of("..", "..", "data", "it-render", "real-model-" + stage + ".txt")
                        .toAbsolutePath().normalize();
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, reply, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // 调试留档失败不影响主链路
                }
                return reply;
            }
        };
        DatasetScriptAuthor author = new DatasetScriptAuthor(model, new DatasetTitleAuthor(model));

        List<HeronDataSource> enabled = registry.stream().filter(s -> "0".equals(s.getEnabled())).toList();
        assertTrue(enabled.size() > 50, "启用源太少，注册表解析可疑：" + enabled.size());
        Set<String> enabledKeys = Set.copyOf(enabled.stream().map(HeronDataSource::getSourceKey).toList());
        HeronDataSourceMapper sourceMapper = Mockito.mock(HeronDataSourceMapper.class);
        Mockito.when(sourceMapper.enabledSources()).thenReturn(enabled);
        HeronDatasetUsageMapper usageMapper = Mockito.mock(HeronDatasetUsageMapper.class);
        Mockito.when(usageMapper.recentSourceKeys(Mockito.any())).thenReturn(Set.of());
        DatasetRouter real = new DatasetRouter(model, sourceMapper, usageMapper);
        DatasetRouter.Route digest = DatasetRouter.digest(enabled);
        System.out.println("DIGEST=" + digest.sourceKey() + " COLUMN="
            + Column.forSource(digest.sourceKey()).displayName());

        DatasetRouter router = new DatasetRouter(null, null, null) {
            @Override
            public Route route(ProductionRunVo run, JsonNode input) {
                Route route = real.route(run, input);
                System.out.println("ROUTE=" + route.sourceKey() + " DIGEST=" + route.digest()
                    + " COLUMN=" + Column.forSource(route.sourceKey()).displayName()
                    + " WEIGHT=" + Column.forSource(route.sourceKey()).weight());
                return route;
            }
        };
        DatasetFetchService fetch = new DatasetFetchService(null, null) {
            @Override
            public Result fetch(ProductionRunVo run, String sourceKey, int limit) {
                String json = adapterFor(sourceKey).fetch(limit);
                List<String> violations = DatasetContract.violations(JSON.readTree(json), enabledKeys);
                assertTrue(violations.isEmpty(), "快照退回：" + String.join("；", violations));
                ProductionArtifactVo artifact = new ProductionArtifactVo(UUID.randomUUID().toString(),
                    "dataset", "dataset.json", json.getBytes(StandardCharsets.UTF_8).length,
                    DigestUtil.sha256Hex(json), "application/json");
                return new Result(json, DigestUtil.sha256Hex(json), artifact);
            }
        };
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
        input.put("title", "中美科技巨头市值排位生变");
        input.put("draft", "英伟达市值重回高位，中美科技公司市值排位出现变化。");
        ProductionRunVo run = new ProductionRunVo(7001L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), JSON.writeValueAsString(input), null);
        ProductionResultVo result = pipeline.execute(run);

        JsonNode content = JSON.readTree(result.resultJson());
        Path preview = repository.resolve("data").resolve("it-render").resolve("data-board-real.mp4");
        Path scriptOut = repository.resolve("data").resolve("it-render").resolve("data-board-real-script.json");
        Files.createDirectories(preview.getParent());
        Files.writeString(scriptOut, content.path("script").toString(), StandardCharsets.UTF_8);
        Files.copy(dataRoot.resolve(byKind(result, "mp4").storageKey()), preview, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("MP4=" + preview.toAbsolutePath());
        System.out.println("SCRIPT=" + scriptOut.toAbsolutePath());
        System.out.println("DURATION_MS=" + content.path("durationMs").asLong());
        assertEquals(11, content.path("checks").size());
    }

    /** 注册表以 SQL 迁移为准；测试不连库，按同样格式把 INSERT 元组读回内存。 */
    static List<HeronDataSource> registry(Path repository) throws IOException {
        Path dir = repository.resolve("script").resolve("sql").resolve("heron");
        List<HeronDataSource> sources = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "0*.sql")) {
            for (Path file : files) {
                sources.addAll(parseSql(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return sources;
    }

    static List<HeronDataSource> parseSql(String sql) {
        List<HeronDataSource> out = new ArrayList<>();
        for (String tuple : tuples(sql)) {
            List<String> field = fields(tuple);
            if (field.size() < 14 || !field.get(3).startsWith("http")) continue;
            HeronDataSource source = new HeronDataSource();
            source.setSourceKey(field.get(0));
            source.setName(field.get(1));
            source.setCategory(field.get(2));
            source.setCadence(field.get(7));
            source.setShapes(field.get(11));
            source.setEnabled(field.get(12));
            source.setProbeStatus(field.get(13));
            out.add(source);
        }
        return out;
    }

    /** 括号深度 + 引号状态一起走，跳过 UTC_TIMESTAMP(3) 这类嵌套括号。 */
    private static List<String> tuples(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (char c : sql.toCharArray()) {
            if (c == '\'') quoted = !quoted;
            if (!quoted) {
                if (c == '(') {
                    depth++;
                    if (depth == 1) {
                        current.setLength(0);
                        continue;
                    }
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        out.add(current.toString());
                        continue;
                    }
                }
            }
            if (depth > 0) current.append(c);
        }
        return out;
    }

    private static List<String> fields(String tuple) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : tuple.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && c == ',') {
                out.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        out.add(current.toString().trim());
        return out;
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
