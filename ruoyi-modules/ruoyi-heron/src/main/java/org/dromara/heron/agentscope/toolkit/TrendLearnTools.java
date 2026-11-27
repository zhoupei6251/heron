package org.dromara.heron.agentscope.toolkit;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.heron.agentscope.research.ResearchBudget;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 学习 Agent 的只读工具面：拆解、模板能力、模板实现与 storyboard。 */
public class TrendLearnTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TEXT_CHARS = 40_000;
    private static final int MAX_FRAME_WIDTH = 1600;
    private static final long MAX_FRAME_BYTES = 3L * 1024 * 1024;

    private final Path breakdown;
    private final Path storyboard;
    private final Path profileRoot;
    private final Path knowledgeRoot;
    private final ResearchBudget budget;

    /**
     * @param properties 生产配置，用于定位 Profile 目录与同级的 creative-knowledge 弹药库
     * @param breakdown  当前视频的拆解 JSON 路径
     * @param storyboard 当前视频的分镜抽帧图路径
     * @param budget     本次学习的预算；null 表示不限
     */
    public TrendLearnTools(HeronProductionProperties properties, Path breakdown, Path storyboard,
                           ResearchBudget budget) {
        this.breakdown = breakdown;
        this.storyboard = storyboard;
        this.profileRoot = properties.getNewsProfile().toAbsolutePath().normalize();
        this.knowledgeRoot = profileRoot.resolveSibling("creative-knowledge");
        this.budget = budget;
    }

    /**
     * 校验模板 key 是否真实存在：目录名合法、目录存在且含 profile.json。
     *
     * @param profileId Profile 目录 key
     * @return true 表示能读到该 Profile 的 profile.json；key 不合法或目录不存在时为 false
     */
    public boolean hasProfile(String profileId) {
        if (profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,80}")) {
            return false;
        }
        Path dir = profileRoot.resolve(profileId);
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("profile.json"));
    }

    /**
     * 读取当前视频的 rea-video 拆解 JSON。
     *
     * @return 拆解 JSON 文本，超过 40000 字符截断
     */
    @Tool(name = "read_breakdown", description = "读取当前视频的 rea-video 拆解 JSON，含镜头、节奏、文字与运动信息", readOnly = true)
    public String readBreakdown() {
        acquireBudget();
        return readText(breakdown, MAX_TEXT_CHARS);
    }

    /**
     * 列出 Profile 目录及各自的风格词汇，用于判断技法能挂到哪套画面语法。
     *
     * @return {@code count}、{@code profiles}（key、profileId、bizLine、canvas、风格能力）与全量 styles
     */
    @Tool(name = "list_profiles", description = "列出 Heron 现有视觉 Profile 及风格词汇，用于判断技法可挂到哪套画面语法", readOnly = true)
    public Map<String, Object> listProfiles() {
        acquireBudget();
        Map<String, Object> styles = readStyles();
        List<Map<String, Object>> profiles = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(profileRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory)
                .filter(path -> Files.isRegularFile(path.resolve("profile.json")))
                .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                JsonNode config = readJson(dir.resolve("profile.json"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", dir.getFileName().toString());
                item.put("profileId", config.path("profileId").asString(dir.getFileName().toString()));
                item.put("bizLine", config.path("bizLine").asString(""));
                item.put("canvas", config.path("canvas"));
                item.put("styleCapability", styles.get(dir.getFileName().toString()));
                profiles.add(item);
            }
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_learn_templates_unavailable", "Profile 目录无法读取", e);
        }
        return Map.of("count", profiles.size(), "profiles", profiles, "styles", styles);
    }

    /**
     * 读取一套 Profile 的 profile.json、风格词汇与 tokens.css。
     *
     * @param profileId Profile 目录 key，必须来自 list_profiles
     * @return 按 profile.json、styles.json、（如有）tokens.css 拼接的文本，超长截断
     */
    @Tool(name = "read_profile", description = "读取一套 Heron 视觉 Profile 的元数据、风格词汇与令牌 CSS；key 必须来自 list_profiles", readOnly = true)
    public String readProfile(@ToolParam(name = "profileId", description = "Profile 目录 key", required = true) String profileId) {
        acquireBudget();
        if (profileId == null || !profileId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new HeronBusinessException(400, "trend_learn_template_invalid", "Profile key 不合法");
        }
        if (!hasProfile(profileId)) {
            throw new HeronBusinessException(404, "trend_learn_template_missing", "Profile 不存在: " + profileId);
        }
        Path dir = profileRoot.resolve(profileId);
        Path configPath = dir.resolve("profile.json");
        JsonNode config = readJson(configPath);
        StringBuilder text = new StringBuilder();
        text.append("## profile.json\n").append(readText(configPath, MAX_TEXT_CHARS)).append("\n\n");
        text.append("## styles.json\n").append(JSON.writeValueAsString(readStyles().get(profileId))).append("\n\n");
        Path tokens = dir.resolve("tokens.css");
        if (Files.isRegularFile(tokens)) {
            text.append("## tokens.css\n").append(readText(tokens, MAX_TEXT_CHARS)).append("\n\n");
        }
        return truncate(text.toString(), MAX_TEXT_CHARS * 2);
    }

    /**
     * 把 storyboard 抽帧图读成 JPEG 返回给模型。
     *
     * @return 一段说明文本 + 一张 Base64 JPEG；图片不可读、编码失败或超过 3MiB 时抛
     *         {@link HeronBusinessException}
     */
    @Tool(name = "view_frame", description = "查看当前视频的 storyboard 分镜抽帧图，判断镜头构图、文字层级和运动变化", readOnly = true)
    public Mono<ToolResultBlock> viewFrame() {
        acquireBudget();
        return Mono.fromSupplier(() -> {
            try {
                BufferedImage source = ImageIO.read(storyboard.toFile());
                if (source == null) {
                    throw new HeronBusinessException(502, "trend_learn_frame_invalid", "storyboard 不是可读图片");
                }
                BufferedImage frame = resize(source);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(frame, "jpg", bytes)) {
                    throw new HeronBusinessException(502, "trend_learn_frame_invalid", "storyboard 无法编码为 JPEG");
                }
                if (bytes.size() > MAX_FRAME_BYTES) {
                    throw new HeronBusinessException(413, "trend_learn_frame_too_large", "storyboard 抽帧超过 3MiB");
                }
                DataBlock image = DataBlock.builder().source(Base64Source.builder()
                    .mediaType("image/jpeg")
                    .data(Base64.getEncoder().encodeToString(bytes.toByteArray())).build())
                    .name("storyboard.jpg").build();
                return ToolResultBlock.of(List.of(
                    TextBlock.builder().text("当前视频的 storyboard 分镜抽帧：").build(), image));
            } catch (IOException e) {
                throw new HeronBusinessException(502, "trend_learn_frame_unavailable", "storyboard 无法读取", e);
            }
        });
    }

    /** 读取 creative-knowledge/styles/template-styles.json；缺失或解析失败直接报错，不返回空表。 */
    private Map<String, Object> readStyles() {
        Path file = knowledgeRoot.resolve("styles/template-styles.json");
        if (!Files.isRegularFile(file)) {
            throw new HeronBusinessException(503, "trend_learn_styles_missing", "模板风格声明缺失");
        }
        try {
            return JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            throw new HeronBusinessException(502, "trend_learn_styles_invalid", "模板风格声明无法解析", e);
        }
    }

    /** 读并解析一个 JSON 文件；失败抛 502，并带上文件名。 */
    private static JsonNode readJson(Path file) {
        try {
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new HeronBusinessException(502, "trend_learn_template_invalid", "模板配置无法解析: " + file.getFileName(), e);
        }
    }

    /** 读文本并截断；文件不存在抛 404，读失败抛 502。 */
    private static String readText(Path file, int max) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new HeronBusinessException(404, "trend_learn_artifact_missing", "学习产物不存在: " + file.getFileName());
            }
            return truncate(Files.readString(file, StandardCharsets.UTF_8), max);
        } catch (IOException e) {
            throw new HeronBusinessException(502, "trend_learn_artifact_unreadable", "学习产物无法读取: " + file.getFileName(), e);
        }
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asString("")));
        return values;
    }

    /** 只缩不放：宽度超过 1600 时等比缩小，否则原样返回。 */
    private static BufferedImage resize(BufferedImage source) {
        if (source.getWidth() <= MAX_FRAME_WIDTH) {
            return source;
        }
        int width = MAX_FRAME_WIDTH;
        int height = Math.max(1, Math.round(source.getHeight() * (float) width / source.getWidth()));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…[已截断]";
    }

    /** 预算到点让本次调用失败，模型会转去基于已有信息输出技法卡。 */
    private void acquireBudget() {
        if (budget == null) {
            return;
        }
        String rejection = budget.tryAcquireTool();
        if (rejection != null) {
            throw new HeronBusinessException(429, rejection, "学习工具预算已用尽，请基于已获得的信息输出技法卡");
        }
    }
}
