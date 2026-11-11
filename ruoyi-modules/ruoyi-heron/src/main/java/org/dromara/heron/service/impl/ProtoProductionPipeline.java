package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.domain.vo.ProtoVersionVo;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.ProtoRp1Author;
import org.dromara.heron.service.ProductionStageReporter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * zee-rp 只做 oracle：以子进程调用 scripts/proto.mjs，不 import 引擎代码。
 * 产物必须逐条满足 ProtoProductionDomain 已锁定的清单契约，缺项一律失败，不做补偿。
 */
public class ProtoProductionPipeline implements IProductionPipeline {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration GENERATE_TIMEOUT = Duration.ofSeconds(180);
    private static final int MAX_RP1_BYTES = 256 * 1024;
    private static final Pattern RP1_PAGE = Pattern.compile("(?m)^page #([A-Za-z0-9_-]{1,80})\\s");
    private static final Pattern RP1_TARGET = Pattern.compile("(?m)^rp/1 target=(\\d+)\\b");
    private static final String SPEC_FILE = "project.rp1";
    private static final String NATIVE_FILE = "project.rp";
    private static final String AUDIT_FILE = "audit-report.json";
    private static final String SHEET_FILE = "pages-sheet.png";

    private final ProtoRp1Author author;
    private final ProductionProcessService processes;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final ProductionStageReporter stages;

    public ProtoProductionPipeline(ProtoRp1Author author, ProductionProcessService processes,
                                   ProductionArtifactService artifacts, HeronProductionProperties properties,
                                   ProductionStageReporter stages) {
        this.author = author;
        this.processes = processes;
        this.artifacts = artifacts;
        this.properties = properties;
        this.stages = stages;
    }

    @Override
    public ProductionResultVo execute(ProductionRunVo run) {
        ProtoVersionVo.InputSnapshot input = ProtoProductionDomain.readInput(run.inputJson());
        Path work = artifacts.prepareWorkDirectory(run);
        Path out = work.resolve("out");
        Path spec = work.resolve(SPEC_FILE);
        String rp1 = author.author(run);
        writeRp1(spec, rp1);
        Map<String, String> environment = Map.of("AXURE_BLANK_TEMPLATE_RP9",
            properties.getPrototypeTemplate().toAbsolutePath().normalize().toString());

        stages.beginStage(run, "check", 35);
        ProductionProcessVo check = processes.run(run, new ProductionProcessBo("node",
            List.of(engineScript().toString(), "check", "--spec", spec.toString()), work,
            CHECK_TIMEOUT, environment));
        if (check.exitCode() != 0) {
            throw new HeronBusinessException(409, "proto_engine_check", failureText(check));
        }
        stages.beginStage(run, "generate", 55);
        ProductionProcessVo generate = processes.run(run, new ProductionProcessBo("node",
            List.of(engineScript().toString(), "generate", "--spec", spec.toString(),
                "--out-dir", out.toString(), "--out-rp", out.resolve(NATIVE_FILE).toString()), work,
            GENERATE_TIMEOUT, environment));
        if (generate.exitCode() != 0) {
            throw new HeronBusinessException(409, "proto_engine_generate", failureText(generate));
        }

        Long versionNo = (long) input.versionNo();
        JsonNode audit = JSON.readTree(read(out.resolve(AUDIT_FILE)));
        Path nativeFile = out.resolve(NATIVE_FILE);
        boolean nativeFormatChecked = nativeFormatMatches(nativeFile, rp1Target(rp1));
        List<String> rp1PageIds = new ArrayList<>();
        Matcher page = RP1_PAGE.matcher(rp1);
        while (page.find()) {
            rp1PageIds.add(page.group(1));
        }
        List<Planned> planned = new ArrayList<>(List.of(
            new Planned("native_rp", nativeFile, "application/octet-stream"),
            new Planned("engine_check", write(work.resolve("engine-check.json"), check.stdout()),
                "application/json")));
        for (String pageId : rp1PageIds) {
            planned.add(new Planned("page_preview", scenePreview(audit, pageId), "image/png"));
        }
        planned.add(new Planned("preview_summary", out.resolve(SHEET_FILE), "image/png"));
        planned.add(new Planned("audit", out.resolve(AUDIT_FILE), "application/json"));
        planned.add(new Planned("rp1", spec, "text/plain"));
        // 先整体校验再逐项搬移：半份定稿产物会被清单契约永久拒绝，也会留下无法回收的孤儿文件。
        for (Planned artifact : planned) {
            if (artifact.file() == null || !Files.isRegularFile(artifact.file())) {
                throw new HeronBusinessException(409, "proto_engine_output_missing",
                    "引擎产物不完整: " + artifact.kind());
            }
        }

        stages.beginStage(run, "finalize", 85);
        List<ProductionArtifactVo> files = new ArrayList<>();
        for (Planned artifact : planned) {
            files.add(artifacts.finalizeFile(run, artifact.kind(), artifact.file(),
                artifact.mediaType(), versionNo));
        }
        List<ProtoVersionVo.PageEvidence> pages = new ArrayList<>();
        for (int index = 0; index < rp1PageIds.size(); index++) {
            ProductionArtifactVo preview = files.get(2 + index);
            pages.add(new ProtoVersionVo.PageEvidence(rp1PageIds.get(index), preview.artifactId()));
        }
        ProductionArtifactVo finalizedNative = files.get(0);
        ProductionArtifactVo engineCheck = files.get(1);
        ProductionArtifactVo summary = files.get(2 + pages.size());
        ProductionArtifactVo rawAudit = files.get(3 + pages.size());
        ProductionArtifactVo source = files.get(4 + pages.size());

        JsonNode gates = audit.path("gates");
        var evidence = new ProtoVersionVo.ManifestEvidence(input.engineCommit(), input.templateManifest(),
            List.copyOf(pages), List.copyOf(rp1PageIds), source.artifactId(), summary.artifactId(),
            finalizedNative.artifactId(), engineCheck.artifactId(),
            audit.path("structural_ok").asBoolean(false), nativeFormatChecked,
            source.sha256(), new ProtoVersionVo.AuditEvidence(gates.path("color_ok").asBoolean(false),
                gates.path("font_ok").asBoolean(false), gates.path("semantic_naming_ok").asBoolean(false),
                gates.path("group_coverage_ok").asBoolean(false), rawAudit.artifactId()));
        return new ProductionResultVo(List.copyOf(files), JSON.writeValueAsString(evidence));
    }

    private record Planned(String kind, Path file, String mediaType) { }

    /** 原生容器自检：魔数 ac ef + 小端版本必须等于 RP/1 声明的 target，声明缺失时永远判不过。 */
    private static boolean nativeFormatMatches(Path rp, int target) {
        try (var in = Files.newInputStream(rp)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == (byte) 0xac && head[1] == (byte) 0xef
                && ((head[2] & 0xFF) | (head[3] & 0xFF) << 8) == target;
        } catch (IOException e) {
            return false;
        }
    }

    private static int rp1Target(String rp1) {
        Matcher header = RP1_TARGET.matcher(rp1);
        return header.find() ? Integer.parseInt(header.group(1)) : -1;
    }

    /** 逐页预览只认引擎审计里 page-default 场景的实际输出路径，不猜文件名。 */
    private static Path scenePreview(JsonNode audit, String pageId) {
        for (JsonNode scene : audit.path("scenes")) {
            if (pageId.equals(scene.path("id").asString()) && "page-default".equals(scene.path("kind").asString())) {
                return Path.of(scene.path("pngPath").asString());
            }
        }
        return null;
    }

    private Path engineScript() {
        return Path.of(properties.getZeeRp()).resolve("scripts").resolve("proto.mjs");
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

    /** 引擎把校验结果写在 stdout，失败时 stderr 常为空；否则原因就丢了。 */
    private static String failureText(ProductionProcessVo process) {
        if (process.stderr() != null && !process.stderr().isBlank()) {
            return process.stderr().strip();
        }
        if (process.stdout() != null && !process.stdout().isBlank()) {
            return process.stdout().strip();
        }
        return "引擎退出码 " + process.exitCode() + "，且没有输出";
    }

    private static void writeRp1(Path spec, String rp1) {
        if (rp1 == null || rp1.getBytes(StandardCharsets.UTF_8).length > MAX_RP1_BYTES) {
            throw new HeronBusinessException(409, "proto_rp1_invalid", "RP/1 源码缺失或超出上限");
        }
        write(spec, rp1);
    }
}
