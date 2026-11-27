package org.dromara.heron.service.impl.shared.production;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视觉 Profile 注册表：校验目录与产线/画幅匹配，读取画布、字幕安全带与资产指纹。
 *
 * <p>目录名即画幅：{@code <id>-landscape} 必须是 1920×1080 横屏，其余必须是 1080×1920 竖屏；
 * 校验或读取失败统一抛 503 {@code news_profile_invalid}，避免横竖错配的资产混进生产。
 */
public final class ProfileRegistry {

    public static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";
    static final String FONT = "fonts/NotoSansSC-Regular.otf";
    static final String GSAP = "vendor/gsap.min.js";
    /** 横屏变体目录后缀：命名即画幅——同一风格的横屏资产放在 &lt;id&gt;-landscape/，画幅词汇封闭为竖 1080×1920 / 横 1920×1080。 */
    static final String LANDSCAPE = "-landscape";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path root;

    public ProfileRegistry(HeronProductionProperties properties) {
        root = properties.getNewsProfile();
    }

    /** 按产线把首选模板键归一成受支持的 Profile 风格；{@code -landscape} 后缀保留横屏画幅。 */
    public static String select(ProductionBizType line, String preferred) {
        boolean landscape = preferred != null && preferred.endsWith(LANDSCAPE);
        String base = landscape ? preferred.substring(0, preferred.length() - LANDSCAPE.length()) : preferred;
        String style = switch (line) {
            case news_explainer -> "article".equals(base) ? "article" : "sprint";
            case data_board -> "data-board-3d".equals(base) ? base : "data-board";
            case trend_insight -> "data-investigation";
            case knowledge_explainer -> "knowledge";
            case music_video -> throw invalid("音乐视频尚无生产执行器");
        };
        return landscape ? style + LANDSCAPE : style;
    }

    static String voice(String value) {
        return value == null || value.isBlank() || "default".equals(value) ? DEFAULT_VOICE : value;
    }

    public record Profile(String id, int version, String line, String mode, String voice, String accent, int width, int height,
                   int subtitleTop, Map<String, Path> files, String fingerprint) {
        Map<String, Object> lock() {
            return Map.of("profileId", id, "styleKey", id, "profileVersion", version,
                "profileSha256", fingerprint, "voiceKey", voice);
        }

        /** 命名即画幅：目录带 -landscape 后缀必须横屏 1920×1080，否则竖屏 1080×1920。 */
        public boolean landscape() {
            return width > height;
        }
    }

    public Profile load(ProductionBizType line, String id, String voice) {
        if (root == null || id == null || !id.equals(select(line, id))
            || voice == null || !voice.matches("[A-Za-z][A-Za-z0-9-]{1,79}")) {
            throw invalid("视觉 Profile 与产线不匹配，或配音标识非法：" + id);
        }
        try {
            Path location = root.toRealPath();
            Path dir = location.resolve(id).toRealPath();
            Path shared = location.resolve("_shared").toRealPath();
            if (!dir.getParent().equals(location) || !shared.getParent().equals(location)) {
                throw invalid("Profile 目录越界：" + id);
            }
            Map<String, Path> files = new LinkedHashMap<>();
            files.put("profile.json", checked(dir, "profile.json"));
            files.put("tokens.css", checked(dir, "tokens.css"));
            files.put("caption.css", checked(dir, "caption.css"));
            files.put("skeleton-rules.json", checked(dir, "skeleton-rules.json"));
            files.put(FONT, checked(shared, FONT));
            files.put(GSAP, checked(shared, GSAP));
            byte[] metadata = Files.readAllBytes(files.get("profile.json"));
            byte[] skeleton = Files.readAllBytes(files.get("skeleton-rules.json"));
            JsonNode profile = JSON.readTree(metadata);
            JsonNode rules = JSON.readTree(skeleton);
            JsonNode canvas = profile.path("canvas");
            int width = canvas.path("width").asInt(0);
            int height = canvas.path("height").asInt(0);
            int version = profile.path("version").asInt(0);
            int subtitleTop = rules.path("subtitleBandY").path("minY").asInt(0);
            String mode = profile.path("visualMode").asText("");
            String accent = profile.path("accentDefault").asText("");
            // 画幅词汇封闭：目录名带 -landscape 后缀必须横屏，其余竖屏。命名与画幅强绑定，防止横竖错配的资产混进来。
            boolean landscape = id.endsWith(LANDSCAPE);
            String base = landscape ? id.substring(0, id.length() - LANDSCAPE.length()) : id;
            int expectWidth = landscape ? 1920 : 1080;
            int expectHeight = landscape ? 1080 : 1920;
            int bandMin = landscape ? 620 : 1100;
            if (!id.equals(profile.path("profileId").asText()) || !line.name().equals(profile.path("bizLine").asText())
                || version < 1 || width != expectWidth || height != expectHeight || !canvas.equals(rules.path("canvas"))
                || subtitleTop < bandMin || subtitleTop > height - 240 || !"skeleton-rules/1".equals(rules.path("schemaVersion").asText())
                || !mode.equals("data-board-3d".equals(base) ? "3d" : "2d") || !accent.matches("#[0-9a-fA-F]{6}")) {
                throw invalid("Profile 元数据、画幅或字幕安全带不合法：" + id);
            }
            return new Profile(id, version, line.name(), mode, voice, accent, width, height, subtitleTop,
                Map.copyOf(files), fingerprint(files, Map.of("profile.json", metadata, "skeleton-rules.json", skeleton)));
        } catch (IOException | tools.jackson.core.JacksonException e) {
            throw new HeronBusinessException(503, "news_profile_invalid", "Profile 资产无法读取：" + id, e);
        }
    }

    /** 按文件名排序拼接「文件名:sha256」后再整体取 SHA-256，保证目录遍历顺序不影响指纹。 */
    static String fingerprint(Map<String, Path> files, Map<String, byte[]> read) throws IOException {
        StringBuilder hashes = new StringBuilder();
        for (String name : files.keySet().stream().sorted().toList()) {
            String hash;
            if (read.containsKey(name)) {
                hash = DigestUtil.sha256Hex(read.get(name));
            } else {
                try (var input = Files.newInputStream(files.get(name))) {
                    hash = DigestUtil.sha256Hex(input);
                }
            }
            hashes.append(name).append(':').append(hash).append('\n');
        }
        return DigestUtil.sha256Hex(hashes.toString());
    }

    private static Path checked(Path directory, String name) throws IOException {
        Path path = directory.resolve(name).toRealPath();
        if (!path.startsWith(directory) || !Files.isRegularFile(path) || Files.size(path) == 0) {
            throw invalid("Profile 文件缺失或不在指定目录：" + name);
        }
        return path;
    }

    private static HeronBusinessException invalid(String message) {
        return new HeronBusinessException(503, "news_profile_invalid", message);
    }
}
