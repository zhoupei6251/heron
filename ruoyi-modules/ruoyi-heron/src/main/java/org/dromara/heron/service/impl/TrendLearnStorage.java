package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.mapper.TrendLearnOssMapper;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 学习原片与拆解产物的对象存储边界；对象键稳定，失败重试不产生新副本。 */
@Service
@RequiredArgsConstructor
public class TrendLearnStorage {

    public static final List<String> RAW_FILES = List.of("breakdown.json", "report.html", "storyboard.jpg", "VIDEO.md");
    private static final long MAX_RAW_BYTES = 20L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronProductionProperties production;
    private final OssService ossService;
    private final TrendLearnOssMapper ossMapper;

    public Stored uploadVideo(HeronTrendVideo video, ReferenceVideoDownloader.Downloaded file) {
        String configKey = configKey();
        String key = prefix(video) + file.sha256() + ".mp4";
        OssClient client = OssFactory.instance(configKey);
        var result = client.upload(key, file.path(), Options.create().setContentType("video/mp4")
            .setMetadata(Map.of("sha256", file.sha256())));
        register(video, configKey, result.key(), result.url(), file.size(), "video/mp4", "trend_video", file.sha256());
        Long ossId = ossMapper.findId(configKey, result.key());
        if (ossId == null) {
            throw new HeronBusinessException(503, "trend_learn_oss_id_missing", "原片已登记但无法取得 sys_oss 主键");
        }
        return new Stored(result.key(), result.url(), file.size(), "video/mp4", ossId);
    }

    public Map<String, Stored> uploadRaw(HeronTrendVideo video, Path outputDir) {
        String configKey = configKey();
        OssClient client = OssFactory.instance(configKey);
        Map<String, Stored> uploaded = new LinkedHashMap<>();
        for (String name : RAW_FILES) {
            Path file = outputDir.resolve(name);
            long size = rawSize(file);
            String key = prefix(video) + "raw/" + name;
            String mediaType = mediaType(name);
            var result = client.upload(key, file, Options.create().setContentType(mediaType));
            register(video, configKey, result.key(), result.url(), size, mediaType, "trend_learn_raw", null);
            uploaded.put(name, new Stored(result.key(), result.url(), size, mediaType, null));
        }
        return Map.copyOf(uploaded);
    }

    public Path downloadVideo(HeronTrendVideo video, Path workDir) {
        if (video.getOssId() == null) {
            throw new HeronBusinessException(409, "trend_learn_video_missing", "学习原片缺少 OSS 指针");
        }
        List<OssDTO> rows = ossService.selectByIds(Long.toString(video.getOssId()));
        if (rows.size() != 1) {
            throw new HeronBusinessException(410, "trend_learn_video_missing", "学习原片 OSS 元数据不存在");
        }
        OssDTO row = rows.get(0);
        Path target = workDir.resolve("source.mp4");
        try {
            Files.createDirectories(workDir);
            OssFactory.instance(row.getService()).download(row.getFileName(), target);
        } catch (RuntimeException | IOException e) {
            throw new HeronBusinessException(502, "trend_learn_video_download_failed", "从 OSS 恢复学习原片失败", e);
        }
        return requireFile(target, Long.MAX_VALUE);
    }

    public Map<String, String> rawKeys(HeronTrendVideo video) {
        return RAW_FILES.stream().collect(Collectors.toMap(name -> name,
            name -> prefix(video) + "raw/" + name));
    }

    public Path downloadRaw(HeronTrendVideo video, Path workDir) {
        String configKey = configKey();
        Path output = workDir.resolve("out");
        try {
            Files.createDirectories(output);
            OssClient client = OssFactory.instance(configKey);
            for (String name : RAW_FILES) {
                client.download(prefix(video) + "raw/" + name, output.resolve(name));
            }
        } catch (RuntimeException | IOException e) {
            throw new HeronBusinessException(502, "trend_learn_raw_download_failed", "从 OSS 恢复拆解产物失败", e);
        }
        for (String name : RAW_FILES) {
            requireRaw(output.resolve(name));
        }
        return output;
    }

    private void register(HeronTrendVideo video, String configKey, String key, String url, long size,
                          String mediaType, String refType, String sha256) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("bizType", "trend_learn");
        ext.put("fileSize", size);
        ext.put("contentType", mediaType);
        ext.put("source", "heron");
        ext.put("refId", Long.toString(video.getId()));
        ext.put("refType", refType);
        ext.put("platform", video.getPlatform());
        ext.put("awemeId", video.getAwemeId());
        ext.put("isTemp", false);
        if (sha256 != null) {
            ext.put("sha256", sha256);
        }
        OssDTO row = new OssDTO();
        row.setService(configKey);
        row.setFileName(key);
        row.setOriginalName(video.getAwemeId() + "-" + Path.of(key).getFileName());
        row.setFileSuffix(extension(key));
        row.setUrl(url);
        row.setExt1(JSON.writeValueAsString(ext));
        try {
            ossService.registerUploaded(row);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "trend_learn_oss_register_failed", "学习产物 OSS 元数据登记失败", e);
        }
    }

    private static Path requireRaw(Path file) {
        return requireFile(file, MAX_RAW_BYTES);
    }

    private static long rawSize(Path file) {
        try {
            return Files.size(requireRaw(file));
        } catch (IOException e) {
            throw new HeronBusinessException(502, "trend_learn_artifact_unreadable", "学习产物无法读取: " + file.getFileName(), e);
        }
    }

    private static Path requireFile(Path file, long maxBytes) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) <= 0) {
                throw new HeronBusinessException(502, "trend_learn_artifact_invalid", "学习产物为空或不存在: " + file.getFileName());
            }
            if (Files.size(file) > maxBytes) {
                throw new HeronBusinessException(413, "trend_learn_artifact_too_large", "学习产物超过允许体积: " + file.getFileName());
            }
            return file;
        } catch (IOException e) {
            throw new HeronBusinessException(502, "trend_learn_artifact_unreadable", "学习产物无法读取: " + file.getFileName(), e);
        }
    }

    private static String prefix(HeronTrendVideo video) {
        return "heron/trend-learn/" + safe(video.getPlatform()) + "/" + safe(video.getAwemeId()) + "/";
    }

    private static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new HeronBusinessException(400, "trend_learn_id_invalid", "学习产物路径含非法平台或作品 ID");
        }
        return value;
    }

    private static String mediaType(String name) {
        String extension = extension(name);
        return switch (extension) {
            case ".json" -> "application/json";
            case ".html" -> "text/html";
            case ".jpg" -> "image/jpeg";
            case ".md" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }

    private static String extension(String key) {
        String name = Path.of(key).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    public record Stored(String key, String url, long size, String mediaType, Long ossId) { }

    private String configKey() {
        String key = production.getOssConfigKey();
        if (key == null || key.isBlank()) {
            throw new HeronBusinessException(503, "trend_learn_oss_not_configured", "学习产物尚未配置 OSS");
        }
        return key;
    }
}
