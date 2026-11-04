package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.vo.ProductionCapabilitiesVo;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 只报告本地执行条件，不触发外部模型/TTS 调用。
 */
@Service
public class ProductionCapabilityService {

    private final ApplicationContext context;
    private final HeronProductionProperties properties;

    public ProductionCapabilityService(ApplicationContext context, HeronProductionProperties properties) {
        this.context = context;
        this.properties = properties;
    }

    public ProductionCapabilitiesVo getCapabilities() {
        return new ProductionCapabilitiesVo(
            capability("newsProductionPipeline", List.of("node", "python", "ffmpeg", "ffprobe", "hyperframes"),
                properties.getNewsTemplate(), 1, null),
            capability("prototypeProductionPipeline", List.of("python", "zee-rp"),
                properties.getPrototypeTemplate(), ProductionServiceImpl.PROTOTYPE_CAPACITY,
                context.getEnvironment().getProperty("heron.prototype.target-axure-version", "")));
    }

    private ProductionCapabilitiesVo.Capability capability(String pipelineBean, List<String> tools,
                                                           Path template, int capacity, String targetAxureVersion) {
        List<String> reasons = new ArrayList<>();
        if (!properties.isWorkerEnabled()) {
            reasons.add("worker_disabled");
        }
        if (!context.containsBean(pipelineBean)) {
            reasons.add("pipeline_missing");
        }
        for (String tool : tools) {
            if (!resolvable(tool)) {
                reasons.add("tool_missing:" + tool);
            }
        }
        // 两条线都从锁定模板渲染：原型是一个 .rp 文件，新闻是一个 composition 目录。
        if (template == null || !Files.exists(template)) {
            reasons.add("template_missing");
        }
        boolean configured = reasons.stream().noneMatch(reason ->
            reason.startsWith("tool_missing:") || reason.equals("template_missing"));
        Path dataRoot = properties.getDataRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(dataRoot) || !Files.isWritable(dataRoot)) {
            reasons.add("data_root_unavailable");
        }
        boolean available = configured && reasons.isEmpty();
        return new ProductionCapabilitiesVo.Capability(configured, available, List.copyOf(reasons), capacity,
            targetAxureVersion);
    }

    private boolean resolvable(String tool) {
        String configured = switch (tool) {
            case "node" -> properties.getNode();
            case "ffmpeg" -> properties.getFfmpeg();
            case "ffprobe" -> properties.getFfprobe();
            case "python" -> properties.getPython();
            case "hyperframes" -> properties.getHyperframes();
            default -> properties.getZeeRp();
        };
        if (configured == null || configured.isBlank()) {
            return false;
        }
        Path path = Path.of(configured);
        // 引擎与渲染器都只作 oracle，调用形态固定为 node <入口>，所以按入口文件所在根判定。
        if ("zee-rp".equals(tool)) {
            return Files.isRegularFile(path.resolve("scripts").resolve("proto.mjs"));
        }
        boolean posix = File.separatorChar == '/';
        if (path.isAbsolute() || tool.indexOf('/') >= 0 || tool.indexOf('\\') >= 0) {
            return Files.isRegularFile(path) && (!posix || Files.isExecutable(path));
        }
        String pathEnvironment = System.getenv("PATH");
        if (pathEnvironment == null || pathEnvironment.isBlank()) {
            return false;
        }
        for (String directory : pathEnvironment.split(File.pathSeparator)) {
            Path candidate = Path.of(directory, tool);
            if (Files.isRegularFile(candidate) && (!posix || Files.isExecutable(candidate))) {
                return true;
            }
            if (Files.isRegularFile(Path.of(directory, tool + ".exe"))) {
                return true;
            }
        }
        return false;
    }
}
