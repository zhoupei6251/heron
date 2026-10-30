package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.*;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.domain.vo.ProtoVersionVo.*;
import org.dromara.heron.mapper.HeronProtoProjectMapper;
import org.dromara.heron.mapper.HeronProtoVersionMapper;
import org.dromara.heron.service.IProductionDomain;
import org.dromara.heron.service.IProductionObjectScope;
import org.dromara.heron.service.IProductionPipeline;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** SP1 原型接缝：只访问业务存储/能力/文件，不反向注入生产调度服务。 */
@Service("prototypeProductionDomain")
public class ProtoProductionDomain implements IProductionDomain, IProductionObjectScope {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private final HeronProtoProjectMapper projectMapper;
    private final HeronProtoVersionMapper versionMapper;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final ObjectProvider<CapabilitySnapshot> capability;
    private final ObjectProvider<IProductionPipeline> pipeline;

    public ProtoProductionDomain(HeronProtoProjectMapper projectMapper, HeronProtoVersionMapper versionMapper,
                                 ProductionArtifactService artifacts, HeronProductionProperties properties,
                                 ObjectProvider<CapabilitySnapshot> capability,
                                 @Qualifier("prototypeProductionPipeline") ObjectProvider<IProductionPipeline> pipeline) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.artifacts = artifacts;
        this.properties = properties;
        this.capability = capability;
        this.pipeline = pipeline;
    }

    /** 仅经 P2/B3/B4 核验的部署能力可以提供此 bean；默认缺失，不能凭路径存在生成能力。 */
    public record CapabilitySnapshot(String engineCommit, Map<String, String> templateManifest,
                                     List<String> supportedWidgets, String modelConfigId) { }

    @Override
    public void lockObject(long bizId) {
        requireTransaction();
        if (projectMapper.lockById(bizId) == null) throw new HeronAccessException();
    }

    @Override
    public long ownerOf(long bizId) {
        var project = projectMapper.selectById(bizId);
        if (project == null) throw new HeronAccessException();
        return project.getOwnerId();
    }

    @Override
    public ProductionReservationVo reserve(ProductionSubmitBo command, long actorId, long taskId) {
        requireTransaction();
        if (command.bizType() != ProductionBizType.prototype) throw new HeronAccessException();
        var project = projectMapper.lockById(command.bizId());
        if (project == null) throw new HeronAccessException();
        if (command.expectedRevision() != project.getRevision()) conflict("草稿已变更，请刷新后生成");
        if (!"0".equals(project.getArchived())) conflict("项目已归档");
        if (project.getActiveTaskId() != null) conflict("项目已有在途任务");
        if (project.getNextVersion() == Integer.MAX_VALUE) conflict("版本序号已达上限");
        var ready = capability.getIfAvailable();
        if (ready == null || pipeline.getIfAvailable() == null || !validCapability(ready)) {
            throw new HeronBusinessException(503, "proto_capability_missing", "原型引擎、模板基准或流水线尚未就绪");
        }
        String requirement = ProtoProjectServiceImpl.normalizeText(project.getRequirement(), "需求", 1, 8000);
        if (!Set.of("web", "mobile").contains(project.getCanvas()) || !"default".equals(project.getThemeKey())) {
            throw new HeronBusinessException(400, "proto_invalid_input", "草稿画布或主题不受支持");
        }
        var input = new InputSnapshot(project.getProjectId(), project.getNextVersion(), project.getRevision(),
            requirement, project.getCanvas(), project.getThemeKey(), ready.engineCommit(),
            new TreeMap<>(ready.templateManifest()), List.copyOf(ready.supportedWidgets()), ready.modelConfigId());
        String inputJson = JSON.writeValueAsString(input);
        requireJsonSize(inputJson);
        var version = new HeronProtoVersion();
        version.setVersionId(IdUtil.getSnowflakeNextId());
        version.setProjectId(project.getProjectId());
        version.setOwnerId(project.getOwnerId());
        version.setVersionNo(project.getNextVersion());
        version.setTaskId(taskId);
        version.setInputSnapshot(inputJson);
        version.setReviewStatus("pending");
        version.setCreateBy(actorId);
        version.setUpdateBy(actorId);
        version.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        version.setUpdateTime(version.getCreateTime());
        if (versionMapper.insert(version) != 1) throw new IllegalStateException("原型版本预留失败");
        int changed = projectMapper.lambda()
            .set(HeronProtoProject::getNextVersion, project.getNextVersion() + 1)
            .set(HeronProtoProject::getActiveTaskId, taskId)
            .set(HeronProtoProject::getUpdateBy, actorId)
            .set(HeronProtoProject::getUpdateTime, version.getCreateTime())
            .eq(HeronProtoProject::getProjectId, project.getProjectId())
            .eq(HeronProtoProject::getRevision, command.expectedRevision())
            .isNull(HeronProtoProject::getActiveTaskId).updateCount();
        if (changed != 1) conflict("项目预留状态已变更");
        return new ProductionReservationVo(version.getVersionId(), project.getOwnerId(), inputJson);
    }

    @Override
    public void complete(HeronProductionTask task, ProductionResultVo result) {
        requireTransaction();
        var project = projectMapper.selectById(task.getBizId());
        if (project == null || !Objects.equals(project.getActiveTaskId(), task.getTaskId())
            || !ProductionStatus.running.name().equals(task.getStatus()) || task.getRunToken() == null) {
            conflict("生产任务不再拥有当前版本");
        }
        var version = versionMapper.lockById(task.getVersionId());
        requireAssociation(version, task);
        if (version.getArtifactManifest() != null || version.getAuditResult() != null) conflict("版本产物已定稿");
        if (result == null) invalidManifest();
        var manifest = verifyManifest(version, task, result.artifacts(), readEvidence(result.resultJson()));
        String artifactJson = JSON.writeValueAsString(manifest.files());
        String auditJson = JSON.writeValueAsString(manifest.evidence());
        requireJsonSize(artifactJson);
        requireJsonSize(auditJson);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int changed = versionMapper.lambda()
            .set(HeronProtoVersion::getArtifactManifest, artifactJson)
            .set(HeronProtoVersion::getEngineCommit, manifest.evidence().engineCommit())
            .set(HeronProtoVersion::getTemplateManifest, JSON.writeValueAsString(manifest.evidence().templateManifest()))
            .set(HeronProtoVersion::getAuditResult, auditJson)
            .set(HeronProtoVersion::getUpdateBy, task.getActorId())
            .set(HeronProtoVersion::getUpdateTime, now)
            .eq(HeronProtoVersion::getVersionId, version.getVersionId())
            .isNull(HeronProtoVersion::getArtifactManifest).isNull(HeronProtoVersion::getAuditResult).updateCount();
        if (changed != 1) conflict("版本产物已变更");
        if (projectMapper.lambda()
            .set(HeronProtoProject::getCurrentVersionId, version.getVersionId())
            .set(HeronProtoProject::getUpdateBy, task.getActorId())
            .set(HeronProtoProject::getUpdateTime, now)
            .eq(HeronProtoProject::getProjectId, project.getProjectId())
            .eq(HeronProtoProject::getActiveTaskId, task.getTaskId()).updateCount() != 1) {
            conflict("项目在途任务已变更");
        }
    }

    @Override
    public void release(HeronProductionTask task) {
        requireTransaction();
        if (!ProductionBizType.prototype.name().equals(task.getBizType())) throw new HeronAccessException();
        projectMapper.lambda().set(HeronProtoProject::getActiveTaskId, null)
            .set(HeronProtoProject::getUpdateBy, task.getActorId())
            .set(HeronProtoProject::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC))
            .eq(HeronProtoProject::getProjectId, task.getBizId())
            .eq(HeronProtoProject::getActiveTaskId, task.getTaskId()).updateCount();
    }

    /** 审核/交付时重新验证全部文件，不能只相信之前的成功状态。 */
    ValidatedManifest verifyVersion(HeronProtoVersion version, HeronProductionTask task) {
        requireAssociation(version, task);
        if (!ProductionStatus.succeeded.name().equals(task.getStatus())) conflict("任务尚未成功");
        var verified = verifyManifest(version, task, readArtifacts(version.getArtifactManifest()),
            readEvidence(version.getAuditResult()));
        if (!Objects.equals(version.getEngineCommit(), verified.evidence().engineCommit())
            || version.getTemplateManifest() == null
            || !JSON.readTree(version.getTemplateManifest()).equals(JSON.valueToTree(verified.evidence().templateManifest()))) {
            invalidManifest();
        }
        return verified;
    }

    private ValidatedManifest verifyManifest(HeronProtoVersion version, HeronProductionTask task,
                                             List<ProductionArtifactVo> files, ManifestEvidence evidence) {
        requireAssociation(version, task);
        InputSnapshot input = readInput(version.getInputSnapshot());
        if (files == null || evidence == null || files.size() < 7 || files.size() > 1000
            || input.projectId() != version.getProjectId() || input.versionNo() != version.getVersionNo()
            || !Objects.equals(input.engineCommit(), evidence.engineCommit())
            || !Objects.equals(input.templateManifest(), evidence.templateManifest())
            || !Boolean.TRUE.equals(evidence.structuralOk()) || !Boolean.TRUE.equals(evidence.nativeFormatChecked())
            || evidence.audit() == null || !evidence.audit().passed()) invalidManifest();
        requireJsonSize(JSON.writeValueAsString(files));
        requireJsonSize(JSON.writeValueAsString(evidence));
        String directory = "protos/" + version.getProjectId() + "/v" + version.getVersionNo()
            + "/a" + task.getAttemptNo() + "-" + task.getRunToken() + "/";
        Map<String, ProductionArtifactVo> byId = new HashMap<>();
        Set<String> storageKeys = new HashSet<>();
        for (var file : files) {
            if (file == null || file.artifactId() == null
                || !file.artifactId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || file.bytes() <= 0 || file.bytes() > MAX_FILE_BYTES || !isHash(file.sha256())
                || file.storageKey() == null || !file.storageKey().startsWith(directory)
                || !file.storageKey().substring(directory.length()).matches(file.artifactId() + "\\.[a-z0-9]{1,8}")
                || !mediaMatches(file) || byId.put(file.artifactId(), file) != null
                || !storageKeys.add(file.storageKey())) invalidManifest();
            // shortcut: SP1 verify 尚未拒绝根内符号链接，共享校验补齐后收敛此检查。
            Path path = properties.getDataRoot().toAbsolutePath().normalize().resolve(file.storageKey());
            for (Path component = path; component != null; component = component.getParent()) {
                if (Files.isSymbolicLink(component)) invalidManifest();
            }
            artifacts.verify(file);
        }
        var rp1 = reference(byId, evidence.rp1ArtifactId(), "rp1");
        var rp = reference(byId, evidence.nativeRpArtifactId(), "native_rp");
        var summary = reference(byId, evidence.previewSummaryArtifactId(), "preview_summary");
        reference(byId, evidence.checkArtifactId(), "engine_check");
        reference(byId, evidence.audit().rawAuditArtifactId(), "audit");
        if (!rp1.sha256().equals(evidence.sourceRp1Sha256())
            || input.templateManifest().containsValue(rp.sha256())) invalidManifest();
        if (evidence.pages() == null || evidence.pages().size() < 2 || evidence.rp1PageIds() == null) invalidManifest();
        Set<String> pages = new HashSet<>();
        Set<String> pageArtifacts = new HashSet<>();
        for (var page : evidence.pages()) {
            if (page == null || page.pageId() == null || !page.pageId().matches("[A-Za-z0-9_-]{1,80}")
                || !pages.add(page.pageId()) || !pageArtifacts.add(page.previewArtifactId())) invalidManifest();
            reference(byId, page.previewArtifactId(), "page_preview");
        }
        if (!pages.equals(new HashSet<>(evidence.rp1PageIds())) || pages.size() != evidence.rp1PageIds().size()
            || files.size() != pages.size() + 5) invalidManifest();
        return new ValidatedManifest(List.copyOf(files), evidence, rp, summary);
    }

    private static boolean mediaMatches(ProductionArtifactVo file) {
        if (file.kind() == null) return false;
        return switch (file.kind()) {
            case "rp1", "audit", "engine_check" -> "application/json".equals(file.mediaType());
            case "native_rp" -> "application/octet-stream".equals(file.mediaType());
            case "page_preview", "preview_summary" -> "image/png".equals(file.mediaType());
            default -> false;
        };
    }

    private static ProductionArtifactVo reference(Map<String, ProductionArtifactVo> files, String id, String kind) {
        var file = files.get(id);
        if (file == null || !kind.equals(file.kind())) invalidManifest();
        return file;
    }

    private static void requireAssociation(HeronProtoVersion version, HeronProductionTask task) {
        if (version == null || task == null || !ProductionBizType.prototype.name().equals(task.getBizType())
            || !Objects.equals(version.getProjectId(), task.getBizId())
            || !Objects.equals(version.getVersionId(), task.getVersionId())
            || !Objects.equals(version.getTaskId(), task.getTaskId())
            || !Objects.equals(version.getOwnerId(), task.getOwnerId())) throw new HeronAccessException();
        if (task.getInputSnapshot() == null || version.getInputSnapshot() == null
            || !JSON.readTree(task.getInputSnapshot()).equals(JSON.readTree(version.getInputSnapshot()))) invalidManifest();
    }

    private static boolean validCapability(CapabilitySnapshot ready) {
        return ready.engineCommit() != null && ready.engineCommit().matches("[0-9a-f]{40,64}")
            && ready.templateManifest() != null && !ready.templateManifest().isEmpty()
            && ready.templateManifest().entrySet().stream().allMatch(e -> e.getKey() != null
                && e.getKey().matches("[A-Za-z0-9_-]{1,80}") && isHash(e.getValue()))
            && ready.supportedWidgets() != null && !ready.supportedWidgets().isEmpty()
            && ready.supportedWidgets().stream().allMatch(w -> w != null && w.matches("[A-Za-z0-9_-]{1,80}"))
            && ready.modelConfigId() != null && ready.modelConfigId().matches("[A-Za-z0-9_-]{1,80}");
    }

    static InputSnapshot readInput(String json) { return readJson(json, InputSnapshot.class); }
    static ManifestEvidence readEvidence(String json) { return readJson(json, ManifestEvidence.class); }
    static List<ProductionArtifactVo> readArtifacts(String json) {
        return Arrays.asList(readJson(json, ProductionArtifactVo[].class));
    }
    private static <T> T readJson(String json, Class<T> type) {
        requireJsonSize(json);
        try {
            T result = JSON.readValue(json, type);
            if (result == null) invalidManifest();
            return result;
        } catch (tools.jackson.core.JacksonException e) {
            throw new HeronBusinessException(409, "proto_manifest_invalid", "版本输入或产物证据不完整");
        }
    }
    private static void requireJsonSize(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) invalidManifest();
    }
    static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("原型生产接缝必须位于 SP1 事务内");
        }
    }
    private static void conflict(String message) { throw new HeronBusinessException(409, "proto_state_conflict", message); }
    private static void invalidManifest() {
        throw new HeronBusinessException(409, "proto_manifest_invalid", "原型完整产物、页面关联或四项审计未通过");
    }
    record ValidatedManifest(List<ProductionArtifactVo> files, ManifestEvidence evidence,
                             ProductionArtifactVo rp, ProductionArtifactVo summary) { }
}
