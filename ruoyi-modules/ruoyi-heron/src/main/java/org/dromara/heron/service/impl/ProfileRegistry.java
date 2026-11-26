package org.dromara.heron.service.impl;

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

final class ProfileRegistry {

    static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";
    static final String FONT = "fonts/NotoSansSC-Regular.otf";
    static final String GSAP = "vendor/gsap.min.js";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path root;

    ProfileRegistry(HeronProductionProperties properties) {
        root = properties.getNewsProfile();
    }

    static String select(ProductionBizType line, String preferred) {
        return switch (line) {
            case news_explainer -> "article".equals(preferred) ? "article" : "sprint";
            case data_board -> "data-board-3d".equals(preferred) ? preferred : "data-board";
            case trend_insight -> "data-investigation";
            case knowledge_explainer -> "knowledge";
            case music_video -> throw invalid("音乐视频尚无生产执行器");
        };
    }

    static String voice(String value) {
        return value == null || value.isBlank() || "default".equals(value) ? DEFAULT_VOICE : value;
    }

    record Profile(String id, int version, String line, String mode, String voice, String accent, int width, int height,
                   int subtitleTop, Map<String, Path> files, String fingerprint) {
        Map<String, Object> lock() {
            return Map.of("profileId", id, "styleKey", id, "profileVersion", version,
                "profileSha256", fingerprint, "voiceKey", voice);
        }
    }

    Profile load(ProductionBizType line, String id, String voice) {
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
            if (!id.equals(profile.path("profileId").asText()) || !line.name().equals(profile.path("bizLine").asText())
                || version < 1 || width != 1080 || height != 1920 || !canvas.equals(rules.path("canvas"))
                || subtitleTop < 1100 || subtitleTop > height - 240 || !"skeleton-rules/1".equals(rules.path("schemaVersion").asText())
                || !mode.equals("data-board-3d".equals(id) ? "3d" : "2d") || !accent.matches("#[0-9a-fA-F]{6}")) {
                throw invalid("Profile 元数据、画幅或字幕安全带不合法：" + id);
            }
            return new Profile(id, version, line.name(), mode, voice, accent, width, height, subtitleTop,
                Map.copyOf(files), fingerprint(files, Map.of("profile.json", metadata, "skeleton-rules.json", skeleton)));
        } catch (IOException | tools.jackson.core.JacksonException e) {
            throw new HeronBusinessException(503, "news_profile_invalid", "Profile 资产无法读取：" + id, e);
        }
    }

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
