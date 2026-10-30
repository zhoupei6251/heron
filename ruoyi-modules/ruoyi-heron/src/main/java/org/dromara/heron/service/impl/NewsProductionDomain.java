package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.*;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.mapper.*;
import org.dromara.heron.service.IProductionDomain;
import org.dromara.heron.service.IProductionObjectScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** 新闻固定业务接缝。只依赖持久层、文件校验和能力，不反向注入生产提交服务。 */
@Service("newsProductionDomain")
@RequiredArgsConstructor
public class NewsProductionDomain implements IProductionDomain, IProductionObjectScope {
    private static final Set<String> KINDS = Set.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest");
    private static final Set<String> GATES = Set.of("schema", "evidence", "audio", "subtitles", "render", "decode",
        "visual", "aigc_metadata", "aigc_visible", "assets");
    private final HeronNewsCandidateMapper candidateMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final ProductionCapabilityService capabilities;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final Environment environment;
    private final ObjectProvider<Clock> clocks;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockObject(long bizId) {
        HeronNewsWork reference = workMapper.selectById(bizId);
        if (reference == null || candidateMapper.lockById(reference.getCandidateId()) == null
            || workMapper.lockById(bizId) == null) throw new HeronAccessException();
    }

    @Override
    public long ownerOf(long bizId) {
        HeronNewsWork work = workMapper.selectById(bizId);
        if (work == null) throw new HeronAccessException();
        return work.getOwnerId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductionReservationVo reserve(ProductionSubmitBo command, long actorId, long taskId) {
        HeronNewsWork work = workMapper.selectById(command.bizId());
        if (work == null) throw new HeronAccessException();
        HeronNewsCandidate candidate = candidateMapper.selectById(work.getCandidateId());
        if (command.bizType() != ProductionBizType.news_video || candidate == null) throw new HeronAccessException();
        if (!Objects.equals(work.getRevision(), command.expectedRevision())) throw conflict("revision_conflict", "草稿版本已变化");
        if (work.getActiveTaskId() != null || candidate.getActiveTaskId() != null) throw conflict("inflight", "该候选已有在途任务");
        if (candidate.getPublishedAt() != null) throw conflict("published", "该候选已实际发布，不可重复生产");
        if (!capabilities.getCapabilities().news().available()) {
            throw new HeronBusinessException(503, "news_capability_unavailable", "新闻流水线或执行能力未就绪");
        }
        Map<String, Object> assets = productionAssets(work);
        if (work.getNextVersion() == Integer.MAX_VALUE) throw conflict("version_limit", "版本序号已达上限");
        long versionId = IdUtil.getSnowflakeNextId();
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("workId", work.getWorkId());
        snapshot.put("candidateId", candidate.getCandidateId());
        snapshot.put("versionNo", work.getNextVersion());
        snapshot.put("revision", work.getRevision());
        snapshot.put("title", work.getTitle());
        snapshot.put("draft", work.getDraft().replace("\r\n", "\n").replace('\r', '\n'));
        snapshot.put("evidence", JsonUtils.parseObject(candidate.getEvidence(), Object.class));
        snapshot.put("assets", assets);
        String input = JsonUtils.toJsonString(canonical(snapshot));
        boundedJson(input);
        HeronVideoVersion version = new HeronVideoVersion();
        version.setVersionId(versionId);
        version.setWorkId(work.getWorkId());
        version.setOwnerId(work.getOwnerId());
        version.setVersionNo(work.getNextVersion());
        version.setTaskId(taskId);
        version.setInputSnapshot(input);
        version.setReviewStatus("pending");
        version.setCreateBy(actorId);
        version.setUpdateBy(actorId);
        version.setCreateTime(now());
        version.setUpdateTime(now());
        if (versionMapper.insert(version) != 1) throw conflict("reserve_failed", "版本预留失败");
        int changed = workMapper.lambda().set(HeronNewsWork::getNextVersion, work.getNextVersion() + 1)
            .set(HeronNewsWork::getActiveTaskId, taskId).set(HeronNewsWork::getUpdateBy, actorId)
            .set(HeronNewsWork::getUpdateTime, now()).eq(HeronNewsWork::getWorkId, work.getWorkId())
            .eq(HeronNewsWork::getRevision, command.expectedRevision()).isNull(HeronNewsWork::getActiveTaskId).updateCount();
        int claimed = candidateMapper.lambda().set(HeronNewsCandidate::getActiveTaskId, taskId)
            .set(HeronNewsCandidate::getUpdateBy, actorId).set(HeronNewsCandidate::getUpdateTime, now())
            .eq(HeronNewsCandidate::getCandidateId, candidate.getCandidateId()).isNull(HeronNewsCandidate::getActiveTaskId)
            .isNull(HeronNewsCandidate::getPublishedAt).updateCount();
        if (changed != 1 || claimed != 1) throw conflict("reserve_conflict", "候选或草稿状态已变化");
        return new ProductionReservationVo(versionId, work.getOwnerId(), input);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(HeronProductionTask task, ProductionResultVo result) {
        HeronVideoVersion version = versionMapper.lockById(task.getVersionId());
        requireTask(version, task);
        if (!"running".equals(task.getStatus()) || !JsonUtils.getJsonMapper().readTree(version.getInputSnapshot())
            .equals(JsonUtils.getJsonMapper().readTree(task.getInputSnapshot()))) throw conflict("completion_conflict", "任务状态或冻结输入不匹配");
        HeronNewsWork work = workMapper.selectById(task.getBizId());
        HeronNewsCandidate candidate = candidateMapper.selectById(work.getCandidateId());
        if (!Objects.equals(work.getActiveTaskId(), task.getTaskId())
            || !Objects.equals(candidate.getActiveTaskId(), task.getTaskId()) || candidate.getPublishedAt() != null
            || version.getArtifactManifest() != null || version.getCleanupStartedAt() != null) {
            throw conflict("completion_conflict", "该版本已完成或不属于当前任务");
        }
        if (result == null) throw conflict("manifest_invalid", "生成结果缺失");
        boundedJson(result.resultJson());
        JsonNode content = JsonUtils.getJsonMapper().readTree(result.resultJson());
        if (!content.isObject() || !content.path("script").isObject() || !content.path("durationMs").isIntegralNumber()
            || content.path("durationMs").asLong() <= 0) throw conflict("result_invalid", "脚本或实测时长缺失");
        String checks = JsonUtils.toJsonString(content.path("checks"));
        validateChecks(checks);
        validateManifest(version, task, result.artifacts(), true);
        String manifest = JsonUtils.toJsonString(result.artifacts());
        boundedJson(manifest);
        verifyReports(result.artifacts(), JsonUtils.toJsonString(content.path("script")), checks);
        int changed = versionMapper.lambda().set(HeronVideoVersion::getScript, JsonUtils.toJsonString(content.path("script")))
            .set(HeronVideoVersion::getArtifactManifest, manifest).set(HeronVideoVersion::getChecks, checks)
            .set(HeronVideoVersion::getDurationMs, content.path("durationMs").asLong())
            .set(HeronVideoVersion::getUpdateBy, task.getActorId()).set(HeronVideoVersion::getUpdateTime, now())
            .eq(HeronVideoVersion::getVersionId, version.getVersionId()).eq(HeronVideoVersion::getTaskId, task.getTaskId())
            .isNull(HeronVideoVersion::getArtifactManifest).isNull(HeronVideoVersion::getScript)
            .isNull(HeronVideoVersion::getChecks).isNull(HeronVideoVersion::getCleanupStartedAt).updateCount();
        if (changed != 1) throw conflict("completion_conflict", "版本结果只能写入一次");
        if (workMapper.lambda().set(HeronNewsWork::getCurrentVersionId, version.getVersionId())
            .set(HeronNewsWork::getUpdateBy, task.getActorId()).set(HeronNewsWork::getUpdateTime, now())
            .eq(HeronNewsWork::getWorkId, task.getBizId()).eq(HeronNewsWork::getActiveTaskId, task.getTaskId()).updateCount() != 1) {
            throw conflict("completion_conflict", "作品在途指针已变化");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(HeronProductionTask task) {
        HeronNewsWork work = workMapper.selectById(task.getBizId());
        if (work == null) throw new HeronAccessException();
        candidateMapper.lambda().set(HeronNewsCandidate::getActiveTaskId, null)
            .set(HeronNewsCandidate::getUpdateBy, task.getActorId()).set(HeronNewsCandidate::getUpdateTime, now())
            .eq(HeronNewsCandidate::getCandidateId, work.getCandidateId()).eq(HeronNewsCandidate::getActiveTaskId, task.getTaskId()).update();
        workMapper.lambda().set(HeronNewsWork::getActiveTaskId, null)
            .set(HeronNewsWork::getUpdateBy, task.getActorId()).set(HeronNewsWork::getUpdateTime, now())
            .eq(HeronNewsWork::getWorkId, task.getBizId()).eq(HeronNewsWork::getActiveTaskId, task.getTaskId()).update();
    }

    /** 查询、审核、下载均核对版本与任务的不可变归属。 */
    public void requireTask(HeronVideoVersion version, HeronProductionTask task) {
        if (version == null || task == null || !ProductionBizType.news_video.name().equals(task.getBizType())
            || !Objects.equals(version.getTaskId(), task.getTaskId()) || !Objects.equals(version.getVersionId(), task.getVersionId())
            || !Objects.equals(version.getWorkId(), task.getBizId()) || !Objects.equals(version.getOwnerId(), task.getOwnerId())) {
            throw new HeronAccessException();
        }
    }

    /** 只接受完整、同版本同尝试的清单；过期查询可只校验描述而不读取文件。 */
    public void validateManifest(HeronVideoVersion version, HeronProductionTask task, List<ProductionArtifactVo> files, boolean verifyFiles) {
        requireTask(version, task);
        if (files == null || files.size() != KINDS.size()) throw conflict("manifest_invalid", "必须具备全部六类产物");
        Set<String> kinds = new HashSet<>();
        Set<String> ids = new HashSet<>();
        String prefix = "videos/" + version.getWorkId() + "/v" + version.getVersionId() + "/a" + task.getAttemptNo() + "-" + task.getRunToken() + "/";
        for (ProductionArtifactVo file : files) {
            if (file == null || !KINDS.contains(file.kind()) || !kinds.add(file.kind()) || file.artifactId() == null
                || !file.artifactId().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !ids.add(file.artifactId())
                || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}") || file.bytes() <= 0
                || file.bytes() > properties.getNewsMaxBytes()) throw conflict("manifest_invalid", "产物描述不完整或重复");
            boolean captionJson = "subtitles".equals(file.kind()) && "application/json".equals(file.mediaType());
            String extension = switch (file.kind()) { case "mp4" -> "mp4"; case "contact_sheet" -> "png"; case "subtitles" -> captionJson ? "json" : "srt"; default -> "json"; };
            String mediaType = switch (file.kind()) { case "mp4" -> "video/mp4"; case "contact_sheet" -> "image/png"; case "subtitles" -> captionJson ? "application/json" : "application/x-subrip"; default -> "application/json"; };
            if (!Objects.equals(file.storageKey(), prefix + file.artifactId() + "." + extension)
                || !Objects.equals(file.mediaType(), mediaType)) throw conflict("artifact_association", "产物不属于本版本或媒体类型错误");
            if (verifyFiles) {
                safePath(file);
                artifacts.verify(file);
            }
        }
    }

    /** DB 审片内容与已校验报告文件必须一致，不能对另一个脚本/闸报告作决议。 */
    public void verifyReports(List<ProductionArtifactVo> files, String script, String checks) {
        for (String kind : List.of("script", "checks")) {
            ProductionArtifactVo file = files.stream().filter(a -> kind.equals(a.kind())).findFirst().orElseThrow();
            if (file.bytes() > 256 * 1024) throw conflict("report_oversized", "脚本或检查报告超过上限");
            try (var input = Files.newInputStream(safePath(file), LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(256 * 1024 + 1);
                if (bytes.length != file.bytes() || !DigestUtil.sha256Hex(bytes).equals(file.sha256())
                    || !JsonUtils.getJsonMapper().readTree(bytes).equals(JsonUtils.getJsonMapper().readTree("script".equals(kind) ? script : checks))) {
                    throw conflict("report_mismatch", "脚本或检查文件与版本报告不一致");
                }
            } catch (IOException | tools.jackson.core.JacksonException e) {
                throw conflict("report_unreadable", "脚本或检查报告不可读");
            }
        }
    }

    /** 闸口必须全部存在且通过；检查明细只接受安全定位符和数值测量。 */
    public void validateChecks(String checks) {
        boundedJson(checks);
        JsonNode list = JsonUtils.getJsonMapper().readTree(checks);
        if (!list.isArray() || list.isEmpty() || list.size() > 200) throw conflict("checks_invalid", "完整检查报告缺失");
        Set<String> seen = new HashSet<>();
        for (JsonNode check : list) {
            if (!check.isObject() || !Set.of("gate", "inputLocator", "passed", "reason", "measured")
                .containsAll(JsonUtils.parseMap(JsonUtils.toJsonString(check)).keySet())) throw conflict("checks_invalid", "检查报告包含未知字段");
            String gate = check.path("gate").asText();
            if (!gate.matches("[a-z][a-z0-9_]{0,63}") || !check.path("passed").isBoolean() || !check.path("passed").asBoolean()
                || !check.path("inputLocator").asText().matches("[a-zA-Z0-9_.:#\\[\\]-]{1,256}")
                || check.path("reason").getNodeType() != tools.jackson.databind.node.JsonNodeType.STRING || check.path("reason").asText().length() > 2000
                || check.path("reason").asText().contains("/") || check.path("reason").asText().contains("\\")
                || !check.path("measured").isObject()) throw conflict("gate_failed", "检查闸缺失、失败或报告不安全");
            for (String key : JsonUtils.parseMap(JsonUtils.toJsonString(check.path("measured"))).keySet()) {
                if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) throw conflict("checks_invalid", "实测指标名称不合法");
            }
            for (JsonNode value : check.path("measured")) {
                if (!value.isNumber() && !value.isBoolean()) throw conflict("checks_invalid", "实测值必须是数值或布尔值");
            }
            seen.add(gate);
        }
        if (!seen.containsAll(GATES)) throw conflict("checks_incomplete", "结构、依据、音轨、字幕、渲染、解码、视觉、AIGC或许可闸缺失");
    }

    /** 新闻文件使用固定服务端布局，逐层拒绝符号链接；复用底座哈希校验。 */
    public Path safePath(ProductionArtifactVo file) {
        String key = file.storageKey();
        if (key == null || !key.matches("videos/[0-9]+/v[0-9]+/a[1-9][0-9]*-[0-9a-f-]{36}/[0-9a-f-]{36}\\.(mp4|png|json|srt)")) {
            throw conflict("file_path_invalid", "产物位置不合法");
        }
        Path root = properties.getDataRoot().toAbsolutePath().normalize();
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw conflict("file_path_invalid", "产物位置不合法");
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw conflict("file_symlink", "产物不允许符号链接");
        }
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root.toRealPath())) {
                throw conflict("file_path_invalid", "产物位置不合法");
            }
        } catch (IOException e) { throw conflict("file_unreadable", "产物不可读"); }
        return path;
    }

    /** 在版本锁内校验并打开；响应读取仍持有同一文件句柄。 */
    public NewsFileVo openFile(HeronVideoVersion version, ProductionArtifactVo file) {
        Path path = safePath(file);
        artifacts.verify(file);
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                if (channel.size() != file.bytes() || !DigestUtil.sha256Hex(Channels.newInputStream(channel)).equals(file.sha256())) {
                    throw conflict("file_corrupt", "文件长度或哈希不符");
                }
                safePath(file);
                channel.position(0);
                String name = "news-" + version.getWorkId() + "-v" + version.getVersionNo()
                    + ("mp4".equals(file.kind()) ? ".mp4" : "-contact.png");
                return new NewsFileVo(name, file.mediaType(), file.bytes(), file.sha256(), Channels.newInputStream(channel));
            } catch (RuntimeException | IOException e) { channel.close(); throw e; }
        } catch (IOException | cn.hutool.core.io.IORuntimeException e) { throw conflict("file_unreadable", "产物不可读"); }
    }

    /** 任一人工保留标记或失败证据都阻止新闻保留期清理。 */
    public boolean hasKeep(HeronProductionTask task, List<ProductionArtifactVo> files) {
        for (ProductionArtifactVo file : files) {
            Path path = safePath(file).getParent();
            while (path != null) {
                if (Files.exists(path.resolve(".keep"), LinkOption.NOFOLLOW_LINKS)) return true;
                path = path.getParent();
            }
        }
        Path taskRoot = properties.getDataRoot().toAbsolutePath().normalize().resolve("work/news_video/" + task.getTaskId());
        for (Path parent = taskRoot; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent) || Files.exists(parent.resolve(".keep"), LinkOption.NOFOLLOW_LINKS)) return true;
        }
        if (!Files.exists(taskRoot, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var paths = Files.walk(taskRoot, 3)) {
            return paths.anyMatch(path -> Files.isSymbolicLink(path) || ".keep".equals(path.getFileName().toString()));
        } catch (IOException e) { throw conflict("keep_unreadable", "无法确认人工保留标记"); }
    }

    private Map<String, Object> productionAssets(HeronNewsWork work) {
        // Deployment-provided identifiers and hashes, not paths or credentials; missing B2 evidence stays unavailable.
        String json = environment.getProperty("heron.news.production-assets");
        if (json == null || json.isBlank()) throw new HeronBusinessException(503, "news_assets_unconfigured", "尚未锁定新闻模板、字体、声音和素材版本");
        Map<String, Object> asset = new TreeMap<>();
        try {
            JsonNode node = JsonUtils.getJsonMapper().readTree(json);
            for (String key : List.of("templateKey", "templateVersion", "fontSha256", "voiceKey", "voiceVersion", "assetManifestSha256")) {
                String value = node.path(key).asText();
                if (!value.matches(key.endsWith("Sha256") ? "[0-9a-f]{64}" : "[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException();
                asset.put(key, value);
            }
        } catch (RuntimeException e) { throw new HeronBusinessException(503, "news_assets_invalid", "锁定资产配置不完整"); }
        if (!work.getTemplateKey().equals(asset.get("templateKey")) || !work.getVoiceKey().equals(asset.get("voiceKey"))) {
            throw new HeronBusinessException(503, "news_assets_unavailable", "选择的模板或声音尚未验证");
        }
        return asset;
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(key.toString(), canonical(item)));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    private void boundedJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 256 * 1024 || !JsonUtils.isJson(json)) {
            throw conflict("json_invalid", "业务 JSON 缺失、非法或超过 256KiB");
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clocks.getIfAvailable(Clock::systemUTC)); }
    private HeronBusinessException conflict(String code, String message) { return new HeronBusinessException(409, "news_" + code, message); }
}
