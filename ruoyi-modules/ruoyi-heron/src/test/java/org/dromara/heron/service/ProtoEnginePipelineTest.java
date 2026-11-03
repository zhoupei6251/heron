package org.dromara.heron.service;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.domain.vo.ProtoVersionVo;
import org.dromara.heron.service.impl.ProtoProductionPipeline;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("原型装配流水线：引擎调用次序与产物定稿")
class ProtoEnginePipelineTest {

    /** 两页 RP/1，页面 ID 满足产物清单的 [A-Za-z0-9_-] 约束。 */
    static final String RP1 = """
        rp/1 target=9 name="清单" domain=web theme=enterprise-light
        page #list name="列表"
          label #head "标题"
        page #detail name="详情"
          label #back "返回"
        """;

    static final String ENGINE_COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String TEMPLATE_SHA = "a".repeat(64);
    static final String INPUT = """
        {"projectId":10,"versionNo":3,"revision":7,"requirement":"清单","canvas":"mobile",
         "themeKey":"enterprise-light","engineCommit":"%s","templateManifest":{"blank-rp9.rp":"%s"},
         "supportedWidgets":["label"],"modelConfigId":"default"}
        """.formatted(ENGINE_COMMIT, TEMPLATE_SHA);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    private HeronProductionProperties properties;
    private ProductionArtifactService artifacts;
    private RecordingProcesses processes;
    private ProtoProductionPipeline pipeline;

    @BeforeEach
    void setUp() {
        properties = new HeronProductionProperties();
        properties.setDataRoot(tempDir);
        properties.setZeeRp(tempDir.resolve("zee-rp").toString());
        properties.setPrototypeTemplate(write("blank-rp9.rp", "blank"));
        artifacts = new ProductionArtifactService(properties);
        processes = new RecordingProcesses(properties, artifacts);
        pipeline = new ProtoProductionPipeline(run -> RP1, processes, artifacts, properties);
    }

    @Test
    @DisplayName("引擎 check 非零退出时不进入装配，stderr 原文进入失败原因")
    void checkFailureStopsBeforeAssembling() {
        processes.reply(new ProductionProcessVo(1, false, false,
            "{\"ok\":false}", "E_TARGET_UNSUPPORTED: native backend supports target 9"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> pipeline.execute(run()));

        assertEquals("proto_engine_check", error.getCode());
        assertTrue(error.getMessage().contains("E_TARGET_UNSUPPORTED"), error.getMessage());
        assertEquals(1, processes.commands.size());
    }

    @Test
    @DisplayName("check 通过后进入装配，空白模板只从 yml 注入引擎环境变量")
    void generatesWithTemplateFromProperties() throws IOException {
        ProductionRunVo run = run();
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.writeOnGenerate(engineOutputs(run));

        pipeline.execute(run);

        assertEquals(2, processes.commands.size());
        ProductionProcessBo check = processes.commands.get(0);
        ProductionProcessBo generate = processes.commands.get(1);
        Path spec = artifacts.prepareWorkDirectory(run).resolve("project.rp1");

        assertEquals(List.of(engineScript(), "check", "--spec", spec.toString()), check.arguments());
        assertEquals(List.of(engineScript(), "generate", "--spec", spec.toString(),
            "--out-dir", spec.getParent().resolve("out").toString(),
            "--out-rp", spec.getParent().resolve("out").resolve("project.rp").toString()), generate.arguments());
        for (ProductionProcessBo command : List.of(check, generate)) {
            assertEquals(Map.of("AXURE_BLANK_TEMPLATE_RP9",
                properties.getPrototypeTemplate().toAbsolutePath().normalize().toString()), command.environment());
        }
    }

    @Test
    @DisplayName("装配成功定稿 2+5 个产物，证据满足原型版本清单契约")
    void finalizesManifest() throws IOException {
        ProductionRunVo run = run();
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.writeOnGenerate(engineOutputs(run));

        ProductionResultVo result = pipeline.execute(run);

        List<ProductionArtifactVo> files = result.artifacts();
        assertEquals(List.of("audit", "engine_check", "native_rp", "page_preview", "page_preview",
            "preview_summary", "rp1"), files.stream()
            .map(ProductionArtifactVo::kind).sorted().toList());
        for (ProductionArtifactVo file : files) {
            assertTrue(file.storageKey().startsWith("protos/10/v3/a1-" + run.runToken() + "/"),
                file.storageKey());
        }
        ProtoVersionVo.ManifestEvidence evidence =
            JSON.readValue(result.resultJson(), ProtoVersionVo.ManifestEvidence.class);
        assertEquals(List.of("list", "detail"), evidence.pages().stream()
            .map(ProtoVersionVo.PageEvidence::pageId).toList());
        assertEquals(List.of("list", "detail"), evidence.rp1PageIds());
        assertEquals(ENGINE_COMMIT, evidence.engineCommit());
        assertEquals(Map.of("blank-rp9.rp", TEMPLATE_SHA), evidence.templateManifest());
        assertEquals(Boolean.TRUE, evidence.structuralOk());
        assertEquals(Boolean.TRUE, evidence.nativeFormatChecked());
        assertTrue(evidence.audit().passed());
        String rp1Id = kindId(files, "rp1", 0);
        assertEquals(rp1Id, evidence.rp1ArtifactId());
        assertEquals(sha256(RP1.getBytes(StandardCharsets.UTF_8)), byId(files, rp1Id).sha256());
        assertEquals(byId(files, rp1Id).sha256(), evidence.sourceRp1Sha256());
        assertEquals(kindId(files, "native_rp", 0), evidence.nativeRpArtifactId());
        assertEquals(kindId(files, "preview_summary", 0), evidence.previewSummaryArtifactId());
        assertEquals(kindId(files, "engine_check", 0), evidence.checkArtifactId());
        assertEquals(kindId(files, "audit", 0), evidence.audit().rawAuditArtifactId());
        Map<String, String> previews = Map.of("list", kindId(files, "page_preview", 0),
            "detail", kindId(files, "page_preview", 1));
        for (var page : evidence.pages()) {
            assertEquals(previews.get(page.pageId()), page.previewArtifactId());
        }
    }

    private String engineScript() {
        return tempDir.resolve("zee-rp").resolve("scripts").resolve("proto.mjs").toString();
    }

    @Test
    @DisplayName("引擎 generate 非零退出时不定稿任何产物")
    void generateFailureFinalizesNothing() throws IOException {
        ProductionRunVo run = run();
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.reply(new ProductionProcessVo(1, false, false, "{\"ok\":false}", "E_RP_BUILD: 容器写入失败"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> pipeline.execute(run));

        assertEquals("proto_engine_generate", error.getCode());
        assertTrue(error.getMessage().contains("E_RP_BUILD"), error.getMessage());
        assertTrue(Files.exists(artifacts.prepareWorkDirectory(run).resolve("project.rp1")));
    }

    @Test
    @DisplayName("引擎逐页预览缺失时先拒绝，不留下半份定稿产物")
    void missingScenePreviewFinalizesNothing() throws IOException {
        ProductionRunVo run = run();
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.reply(new ProductionProcessVo(0, false, false, "{\"ok\":true}", ""));
        processes.writeOnGenerate(engineOutputs(run, """
            [{"id":"list","kind":"page-default","pngPath":"%s/list@2x.png"}]"""));

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> pipeline.execute(run));

        assertEquals("proto_engine_output_missing", error.getCode());
        assertTrue(Files.exists(artifacts.prepareWorkDirectory(run).resolve("out/project.rp")));
    }

    /** 引擎 generate 落到 out-dir 的真实形状：审计 JSON、逐页 PNG、联络表、原生 .rp。 */
    private Map<String, byte[]> engineOutputs(ProductionRunVo run) {
        return engineOutputs(run, """
            [{"id":"list","kind":"page-default","pngPath":"%1$s/list@2x.png"},
             {"id":"detail","kind":"page-default","pngPath":"%1$s/detail@2x.png"}]""");
    }

    private Map<String, byte[]> engineOutputs(ProductionRunVo run, String scenes) {
        Path out = artifacts.prepareWorkDirectory(run).resolve("out");
        String audit = """
            {"structural_ok":true,"gates":{"color_ok":true,"font_ok":true,
             "semantic_naming_ok":true,"group_coverage_ok":true},
             "pages":[{"slug":"list"},{"slug":"detail"}],
             "scenes":%s}
            """.formatted(scenes.formatted(out.toString().replace('\\', '/')));
        return Map.of(
            "out/audit-report.json", audit.getBytes(StandardCharsets.UTF_8),
            "out/list@2x.png", "png-list".getBytes(StandardCharsets.UTF_8),
            "out/detail@2x.png", "png-detail".getBytes(StandardCharsets.UTF_8),
            "out/pages-sheet.png", "sheet".getBytes(StandardCharsets.UTF_8),
            "out/project.rp", new byte[] {(byte) 0xac, (byte) 0xef, 9, 0});
    }

    private static ProductionArtifactVo byId(List<ProductionArtifactVo> files, String artifactId) {
        return files.stream().filter(f -> f.artifactId().equals(artifactId)).findFirst().orElseThrow();
    }

    private static String kindId(List<ProductionArtifactVo> files, String kind, int index) {
        return files.stream().filter(f -> f.kind().equals(kind)).map(ProductionArtifactVo::artifactId)
            .toList().get(index);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static ProductionRunVo run() {
        return new ProductionRunVo(9101L, ProductionBizType.prototype, 10L, 5L, 1,
            UUID.randomUUID().toString(), INPUT, null);
    }

    private Path write(String name, String content) {
        try {
            return Files.writeString(tempDir.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 只记录命令、按脚本回复并落引擎产物；真实子进程由 Linux 集成测试覆盖。 */
    static class RecordingProcesses extends ProductionProcessService {

        final List<ProductionProcessBo> commands = new ArrayList<>();
        final Deque<ProductionProcessVo> replies = new ArrayDeque<>();
        final List<Map<String, byte[]>> generated = new ArrayList<>();
        private final ProductionArtifactService artifacts;
        private int generateIndex;

        RecordingProcesses(HeronProductionProperties properties, ProductionArtifactService artifacts) {
            super(properties, artifacts);
            this.artifacts = artifacts;
        }

        void reply(ProductionProcessVo result) {
            replies.add(result);
        }

        void writeOnGenerate(Map<String, byte[]> files) {
            generated.add(files);
        }

        @Override
        public ProductionProcessVo run(ProductionRunVo run, ProductionProcessBo command) {
            commands.add(command);
            if (command.arguments().contains("generate") && generateIndex < generated.size()) {
                writeOutputs(generated.get(generateIndex++), run);
            }
            ProductionProcessVo result = replies.poll();
            if (result == null) {
                throw new IllegalStateException("没有为命令准备回复: " + command.arguments());
            }
            return result;
        }

        private void writeOutputs(Map<String, byte[]> files, ProductionRunVo run) {            Path work = artifacts.prepareWorkDirectory(run);
            try {
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    Path target = work.resolve(entry.getKey());
                    Files.createDirectories(target.getParent());
                    Files.write(target, entry.getValue());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
