package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.HeronSceneRework;
import org.dromara.heron.domain.HeronTaskStep;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronSceneReworkMapper;
import org.dromara.heron.mapper.HeronTaskStepMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.ISceneReworkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 场景返工的生产事务入口。
 *
 * <p>调用方只提供执行中且已认领的 run、sceneId 与替换画面。上一版由作品当前版本指针决定，
 * 在候选、作品、上一版与新版本的数据库行锁内读取 manifest/script；渲染期间保留这些锁，
 * 避免另一笔完成操作换掉当前版本。事务同时提交版本结果、审核/发布失效与 V64 审计。</p>
 */
@Service
@RequiredArgsConstructor
public class SceneReworkService implements ISceneReworkService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NewsProductionPipeline pipeline;
    private final NewsProductionDomain domain;
    private final HeronProductionTaskMapper taskMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final HeronSceneReworkMapper reworkMapper;
    private final HeronTaskStepMapper stepMapper;
    private final ProductionArtifactService artifacts;
    private final ProductionNotificationService notifications;
    private final PlatformTransactionManager transactionManager;

    @Override
    public ProductionResultVo rework(ProductionRunVo run, String sceneId, JsonNode replacementScene) {
        if (run == null || run.bizType() != ProductionBizType.news_video || sceneId == null || sceneId.isBlank()
            || replacementScene == null || !replacementScene.isObject()) {
            throw new HeronBusinessException(400, "scene_rework_invalid", "返工运行上下文、scene_id 或替换场景不完整");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        ProductionResultVo result;
        try {
            result = transaction.execute(status -> executeLocked(run, sceneId, replacementScene));
        } catch (RuntimeException e) {
            artifacts.releaseAttempt(run, false, () -> isStillUncommitted(run));
            throw e;
        }
        artifacts.releaseAttempt(run, true);
        return result;
    }

    private boolean isStillUncommitted(ProductionRunVo run) {
        TransactionTemplate check = new TransactionTemplate(transactionManager);
        check.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        return Boolean.TRUE.equals(check.execute(status -> {
            HeronProductionTask task = taskMapper.lockById(run.taskId());
            HeronVideoVersion target = versionMapper.lockById(run.versionId());
            HeronNewsWork work = workMapper.lockById(run.bizId());
            return task != null && target != null && work != null
                && ProductionStatus.running.name().equals(task.getStatus())
                && Objects.equals(task.getRunToken(), run.runToken())
                && Objects.equals(task.getVersionId(), target.getId())
                && Objects.equals(task.getBizId(), target.getWorkId())
                && target.getArtifactManifest() == null && target.getChecks() == null
                && !Objects.equals(work.getCurrentVersionId(), target.getId());
        }));
    }

    private ProductionResultVo executeLocked(ProductionRunVo run, String sceneId, JsonNode replacementScene) {
        domain.lockObject(run.bizId());
        HeronProductionTask task = taskMapper.lockById(run.taskId());
        requireOwnedTask(task, run);
        HeronNewsWork work = workMapper.lockById(run.bizId());
        HeronNewsCandidate candidate = candidateMapper.lockById(work.getCandidateId());
        if (!Objects.equals(work.getOwnerId(), task.getOwnerId())
            || !Objects.equals(work.getActiveTaskId(), task.getId())
            || !Objects.equals(candidate.getActiveTaskId(), task.getId())
            || candidate.getPublishedAt() != null || work.getCurrentVersionId() == null) {
            throw conflict("scene_rework_source_conflict", "作品、候选或在途任务不属于本次返工");
        }
        HeronVideoVersion previous = versionMapper.lockById(work.getCurrentVersionId());
        HeronVideoVersion target = versionMapper.lockById(run.versionId());
        domain.requireTask(target, task);
        if (previous == null || !Objects.equals(previous.getWorkId(), work.getId())
            || !Objects.equals(previous.getOwnerId(), work.getOwnerId()) || previous.getId().equals(target.getId())
            || previous.getArtifactManifest() == null || previous.getScript() == null
            || previous.getChecks() == null || previous.getDurationMs() == null || previous.getDurationMs() <= 0
            || previous.getCleanupStartedAt() != null || target.getArtifactManifest() != null
            || target.getScript() != null || target.getChecks() != null || target.getCleanupStartedAt() != null) {
            throw conflict("scene_rework_source_invalid", "上一版未完成、已清理或新版本不是待写入状态");
        }
        if (!JSON.readTree(run.inputJson()).equals(JSON.readTree(target.getInputSnapshot()))
            || !JSON.readTree(task.getInputSnapshot()).equals(JSON.readTree(target.getInputSnapshot()))) {
            throw conflict("scene_rework_source_invalid", "返工运行输入与新版本冻结快照不一致");
        }
        HeronProductionTask previousTask = taskMapper.selectById(previous.getTaskId());
        domain.requireTask(previous, previousTask);
        List<ProductionArtifactVo> previousArtifacts = readArtifacts(previous.getArtifactManifest());
        String manifestSha = DigestUtil.sha256Hex(JSON.writeValueAsString(previousArtifacts));
        domain.validateManifest(previous, previousTask, previousArtifacts, true);
        domain.validateChecks(previous.getChecks(), previous);
        domain.verifyReports(previousArtifacts, previous.getScript(), previous.getChecks());

        ObjectNode previousResult = JSON.createObjectNode();
        previousResult.put("sourceVersionId", previous.getId());
        previousResult.put("artifactManifestSha256", manifestSha);
        previousResult.set("script", JSON.readTree(previous.getScript()));
        previousResult.put("durationMs", previous.getDurationMs());
        if (previous.getContentProfile() != null) {
            previousResult.set("contentProfile", JSON.readTree(previous.getContentProfile()));
        }
        if (previous.getQualityReport() != null) {
            previousResult.set("qualityReport", JSON.readTree(previous.getQualityReport()));
        }
        for (String kind : List.of("dataset", "trend", "citations", "asset_manifest")) {
            ProductionArtifactVo artifact = byKind(previousArtifacts, kind);
            if (artifact != null) {
                JsonNode snapshot = readArtifactJson(artifact);
                previousResult.set(kind, snapshot);
                if ("asset_manifest".equals(kind) && snapshot.path("mediaSelections").isArray()) {
                    previousResult.set("mediaSelections", snapshot.path("mediaSelections"));
                }
            }
        }
        SceneReworkRequest request = new SceneReworkRequest(sceneId, replacementScene, previous.getId(), manifestSha,
            previousArtifacts, JSON.writeValueAsString(previousResult));
        ProductionResultVo result = pipeline.reworkScene(run, request);
        completeLocked(task, work, candidate, previous, target, result);
        return result;
    }

    private void completeLocked(HeronProductionTask task, HeronNewsWork work, HeronNewsCandidate candidate,
                                HeronVideoVersion previous, HeronVideoVersion target, ProductionResultVo result) {
        JsonNode content = JSON.readTree(result.resultJson());
        JsonNode rework = content.path("sceneRework");
        if (!rework.isObject() || rework.path("previousVersionId").asLong(0) != previous.getId()
            || !DigestUtil.sha256Hex(JSON.writeValueAsString(result.artifacts()))
                .equals(content.path("artifactManifestSha256").asString(""))) {
            throw conflict("scene_rework_result_invalid", "返工结果没有绑定数据库锁定的上一版");
        }
        domain.validateManifest(target, task, result.artifacts(), true);
        domain.validateChecks(JSON.writeValueAsString(content.path("checks")), target);
        domain.verifyReports(result.artifacts(), JSON.writeValueAsString(content.path("script")),
            JSON.writeValueAsString(content.path("checks")));
        artifacts.register(taskRun(task), result.artifacts());

        String script = JSON.writeValueAsString(content.path("script"));
        String checks = JSON.writeValueAsString(content.path("checks"));
        String manifest = JSON.writeValueAsString(result.artifacts());
        LocalDateTime now = LocalDateTime.now();
        int changed = versionMapper.lambda()
            .set(HeronVideoVersion::getScript, script).set(HeronVideoVersion::getArtifactManifest, manifest)
            .set(HeronVideoVersion::getChecks, checks).set(HeronVideoVersion::getDurationMs, content.path("durationMs").asLong())
            .set(HeronVideoVersion::getQualityReport, content.path("qualityReport").isObject()
                ? JSON.writeValueAsString(content.path("qualityReport")) : null)
            .set(HeronVideoVersion::getContentProfile, content.path("contentProfile").isObject()
                ? JSON.writeValueAsString(content.path("contentProfile")) : null)
            .set(HeronVideoVersion::getEditorialReview, null).set(HeronVideoVersion::getReviewStatus, "pending")
            .set(HeronVideoVersion::getReviewBy, null).set(HeronVideoVersion::getReviewAt, null)
            .set(HeronVideoVersion::getReviewReason, null).set(HeronVideoVersion::getReviewVideoSha, null)
            .set(HeronVideoVersion::getReviewContactSha, null).set(HeronVideoVersion::getPublishSource, null)
            .set(HeronVideoVersion::getPublishUrl, null).set(HeronVideoVersion::getPublishAccount, null)
            .set(HeronVideoVersion::getPublishAt, null).set(HeronVideoVersion::getPublishBy, null)
            .set(HeronVideoVersion::getPublishVideoSha, null)
            .set(HeronVideoVersion::getUpdateBy, task.getActorId()).set(HeronVideoVersion::getUpdateTime, now)
            .eq(HeronVideoVersion::getId, target.getId()).eq(HeronVideoVersion::getTaskId, task.getId())
            .isNull(HeronVideoVersion::getArtifactManifest).isNull(HeronVideoVersion::getScript)
            .isNull(HeronVideoVersion::getChecks).isNull(HeronVideoVersion::getCleanupStartedAt).updateCount();
        if (changed != 1) {
            throw conflict("scene_rework_completion_conflict", "新版本结果只能写入一次");
        }
        if (workMapper.lambda().set(HeronNewsWork::getCurrentVersionId, target.getId())
            .set(HeronNewsWork::getUpdateBy, task.getActorId()).set(HeronNewsWork::getUpdateTime, now)
            .eq(HeronNewsWork::getId, work.getId()).eq(HeronNewsWork::getCurrentVersionId, previous.getId())
            .updateCount() != 1) {
            throw conflict("scene_rework_completion_conflict", "上一版当前版本指针已变化");
        }
        insertAudit(target, rework, checks, task.getActorId(), now);
        if (taskMapper.lambda().set(HeronProductionTask::getStatus, ProductionStatus.succeeded.name())
            .set(HeronProductionTask::getProgress, 100).set(HeronProductionTask::getFinishedAt, now)
            .set(HeronProductionTask::getLeaseUntil, null).set(HeronProductionTask::getErrorCode, null)
            .set(HeronProductionTask::getErrorReason, null).set(HeronProductionTask::getUpdateTime, now)
            .eq(HeronProductionTask::getId, task.getId()).eq(HeronProductionTask::getStatus, ProductionStatus.running.name())
            .updateCount() != 1) {
            throw conflict("scene_rework_completion_conflict", "返工任务状态已变化");
        }
        stepMapper.lambda().set(HeronTaskStep::getStatus, ProductionStatus.succeeded.name())
            .set(HeronTaskStep::getFinishedAt, now).set(HeronTaskStep::getUpdateTime, now)
            .eq(HeronTaskStep::getTaskId, task.getId()).eq(HeronTaskStep::getStatus, ProductionStatus.running.name()).update();
        candidateMapper.lambda().set(HeronNewsCandidate::getActiveTaskId, null)
            .set(HeronNewsCandidate::getUpdateBy, task.getActorId()).set(HeronNewsCandidate::getUpdateTime, now)
            .eq(HeronNewsCandidate::getId, candidate.getId()).eq(HeronNewsCandidate::getActiveTaskId, task.getId()).update();
        workMapper.lambda().set(HeronNewsWork::getActiveTaskId, null)
            .set(HeronNewsWork::getUpdateBy, task.getActorId()).set(HeronNewsWork::getUpdateTime, now)
            .eq(HeronNewsWork::getId, work.getId()).eq(HeronNewsWork::getActiveTaskId, task.getId()).update();
        notifications.terminal(task.getId(), ProductionBizType.news_video, task.getActorId(), task.getOwnerId(),
            ProductionStatus.succeeded);
    }

    private void insertAudit(HeronVideoVersion target, JsonNode rework, String checks, long actorId, LocalDateTime now) {
        HeronSceneRework audit = new HeronSceneRework();
        audit.setSceneReworkId(IdUtil.getSnowflakeNextId());
        audit.setVersionId(target.getId());
        audit.setPreviousVersionId(rework.path("previousVersionId").asLong());
        audit.setSceneId(rework.path("sceneId").asString(""));
        audit.setSceneIndex(rework.path("sceneIndex").asInt(-1));
        audit.setStartMs(rework.path("startMs").asLong(-1));
        audit.setEndMs(rework.path("endMs").asLong(-1));
        audit.setPreviousVideoSha256(rework.path("previousVideoSha256").asString(""));
        audit.setSceneVideoSha256(rework.path("sceneVideoSha256").asString(""));
        audit.setVideoSha256(rework.path("videoSha256").asString(""));
        audit.setContactSheetSha256(rework.path("contactSheetSha256").asString(""));
        audit.setScriptSha256(rework.path("scriptSha256").asString(""));
        audit.setPreviousScriptSha256(rework.path("previousScriptSha256").asString(""));
        audit.setPreviousManifestSha256(rework.path("previousManifestSha256").asString(""));
        audit.setAudioSha256(rework.path("audioSha256").asString(""));
        audit.setRenderCalls(rework.path("renderCalls").asInt());
        audit.setTtsCalls(rework.path("ttsCalls").asInt());
        audit.setDurationMs(rework.path("durationMs").asLong());
        audit.setChecksJson(checks);
        audit.setCreateBy(actorId);
        audit.setUpdateBy(actorId);
        audit.setCreateTime(now);
        audit.setUpdateTime(now);
        if (audit.getSceneIndex() < 0 || audit.getStartMs() < 0 || audit.getEndMs() <= audit.getStartMs()
            || audit.getRenderCalls() != 1 || audit.getTtsCalls() != 0 || reworkMapper.insert(audit) != 1) {
            throw conflict("scene_rework_audit_failed", "场景返工审计写入失败");
        }
    }

    private ProductionRunVo taskRun(HeronProductionTask task) {
        return new ProductionRunVo(task.getId(), ProductionBizType.news_video, task.getBizId(), task.getVersionId(),
            task.getAttemptNo(), task.getRunToken(), task.getInputSnapshot(), null);
    }

    private static List<ProductionArtifactVo> readArtifacts(String manifest) {
        try {
            JsonNode node = JSON.readTree(manifest);
            if (node == null || !node.isArray()) {
                throw new IllegalArgumentException();
            }
            List<ProductionArtifactVo> files = new ArrayList<>();
            node.forEach(item -> files.add(JSON.convertValue(item, ProductionArtifactVo.class)));
            return files;
        } catch (RuntimeException e) {
            throw conflict("scene_rework_source_invalid", "数据库上一版产物清单不是有效 JSON");
        }
    }

    private JsonNode readArtifactJson(ProductionArtifactVo artifact) {
        try (var input = artifacts.openVerified(artifact)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024 || bytes.length != artifact.bytes()
                || !DigestUtil.sha256Hex(bytes).equals(artifact.sha256())) {
                throw conflict("scene_rework_source_invalid", "上一版快照产物指纹不匹配");
            }
            return JSON.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            throw conflict("scene_rework_source_invalid", "上一版快照产物不可读");
        }
    }

    private static ProductionArtifactVo byKind(List<ProductionArtifactVo> files, String kind) {
        return files.stream().filter(file -> kind.equals(file.kind())).findFirst().orElse(null);
    }

    private static void requireOwnedTask(HeronProductionTask task, ProductionRunVo run) {
        if (task == null || !ProductionBizType.news_video.name().equals(task.getBizType())
            || !Objects.equals(task.getBizId(), run.bizId()) || !Objects.equals(task.getVersionId(), run.versionId())
            || !ProductionStatus.running.name().equals(task.getStatus())
            || !Objects.equals(task.getRunToken(), run.runToken()) || task.getAttemptNo() != run.attemptNo()) {
            throw conflict("scene_rework_task_conflict", "返工任务归属、租约或状态已变化");
        }
    }

    private static HeronBusinessException conflict(String code, String message) {
        return new HeronBusinessException(409, "news_" + code, message);
    }
}
