package org.dromara.heron.service.impl.shared.production;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
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

    /**
     * 返回两条能力声明：当前第一个可用的视频线，以及音乐视频线。
     *
     * <p>只检查本地条件（worker 开关、流水线 Bean、工具可执行、Profile 资产、数据目录），
     * 不调用模型或 TTS。
     */
    public ProductionCapabilitiesVo getCapabilities() {
        ProductionBizType first = ProductionBizType.videoLanes().stream()
            .filter(line -> context.containsBean(line.pipelineBean())).findFirst().orElse(ProductionBizType.news_explainer);
        return new ProductionCapabilitiesVo(capability(first), capability(ProductionBizType.music_video));
    }

    ProductionCapabilitiesVo.Capability capability(ProductionBizType line) {
        return line == ProductionBizType.music_video ? capability(line.pipelineBean(), List.of(), null, 0)
            : capability(line.pipelineBean(), List.of("node", "python", "ffmpeg", "ffprobe", "hyperframes"),
                properties.getNewsProfile(), 1);
    }

    private ProductionCapabilitiesVo.Capability capability(String pipelineBean, List<String> tools,
                                                           Path profiles, int capacity) {
        List<String> reasons = new ArrayList<>();
        if (!properties.isWorkerEnabled()) {
            reasons.add("worker_disabled");
        }
        if (pipelineBean == null || !context.containsBean(pipelineBean)) {
            reasons.add("pipeline_missing");
        }
        for (String tool : tools) {
            if (!resolvable(tool)) {
                reasons.add("tool_missing:" + tool);
            }
        }
        if (pipelineBean != null && (profiles == null
            || !Files.isRegularFile(profiles.resolve("_shared").resolve(ProfileRegistry.FONT))
            || !Files.isRegularFile(profiles.resolve("_shared").resolve(ProfileRegistry.GSAP)))) {
            reasons.add("profile_assets_missing");
        }
        boolean configured = pipelineBean != null && reasons.stream().noneMatch(reason ->
            reason.startsWith("tool_missing:") || reason.equals("profile_assets_missing"));
        Path dataRoot = properties.getDataRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(dataRoot) || !Files.isWritable(dataRoot)) {
            reasons.add("data_root_unavailable");
        }
        boolean available = configured && reasons.isEmpty();
        return new ProductionCapabilitiesVo.Capability(configured, available, List.copyOf(reasons), capacity);
    }

    /** 工具配置可以是绝对/含路径分隔符的路径，也可以只是命令名：后者按 PATH 解析，Windows 再试 .exe。 */
    private boolean resolvable(String tool) {
        String configured = switch (tool) {
            case "node" -> properties.getNode();
            case "ffmpeg" -> properties.getFfmpeg();
            case "ffprobe" -> properties.getFfprobe();
            case "python" -> properties.getPython();
            case "hyperframes" -> properties.getHyperframes();
            default -> null;
        };
        if (configured == null || configured.isBlank()) {
            return false;
        }
        Path path = Path.of(configured);
        boolean posix = File.separatorChar == '/';
        if (path.isAbsolute() || configured.indexOf('/') >= 0 || configured.indexOf('\\') >= 0) {
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
