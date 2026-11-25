package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronProductionTask;
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
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用真实产物服务验证成功记账释放、runToken 隔离与已提交字节保护。 */
@DisplayName("场景返工：真实产物清理安全")
class SceneReworkArtifactSafetyTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void duplicateAndForgedAttemptsCannotDeleteCommittedLocalOrOssArtifact(@TempDir Path root) throws Exception {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(root.resolve("data"));
        properties.setOssConfigKey("qiniu");
        OssService registry = mock(OssService.class);
        OssClient oss = mock(OssClient.class);
        org.dromara.common.oss.model.GetObjectResult head = mock(org.dromara.common.oss.model.GetObjectResult.class);
        when(head.size()).thenReturn(9L);
        when(head.metadata()).thenReturn(java.util.Map.of("sha256", DigestUtil.sha256Hex("new-video")));
        doReturn(head).when(oss).download(anyString(),
            org.mockito.ArgumentMatchers.<java.util.function.BiFunction<org.dromara.common.oss.model.GetObjectResult, java.io.InputStream, org.dromara.common.oss.model.GetObjectResult>>any());
        when(oss.upload(anyString(), any(Path.class), any(Options.class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            return new PutObjectResult("https://oss.test/" + key, key, "etag", 8);
        });
        ProductionArtifactService artifacts = new ProductionArtifactService(properties, registry);
        NewsProductionPipeline pipeline = mock(NewsProductionPipeline.class);
        when(pipeline.reworkScene(any(), any())).thenAnswer(call -> {
            ProductionRunVo run = call.getArgument(0);
            Path file = artifacts.prepareWorkDirectory(run).resolve("final.mp4");
            Files.writeString(file, "new-video");
            ProductionArtifactVo video = artifacts.finalizeFile(run, "mp4", file, "video/mp4");
            ObjectNode content = JSON.createObjectNode();
            content.put("sourceVersionId", run.versionId());
            content.put("artifactManifestSha256", DigestUtil.sha256Hex(JSON.writeValueAsString(List.of(video))));
            content.put("durationMs", 41_500L);
            content.set("script", JSON.readTree("{\"scenes\":[]}"));
            content.set("checks", JSON.createArrayNode());
            ObjectNode rework = content.putObject("sceneRework");
            rework.put("previousVersionId", 3L);
            rework.put("previousManifestSha256", DigestUtil.sha256Hex("[]"));
            rework.put("previousScriptSha256", "a".repeat(64));
            rework.put("sceneId", "scene_001");
            rework.put("sceneIndex", 0);
            rework.put("startMs", 0L);
            rework.put("endMs", 10_000L);
            for (String field : List.of("previousVideoSha256", "sceneVideoSha256", "videoSha256",
                "contactSheetSha256", "scriptSha256", "audioSha256")) {
                rework.put(field, "a".repeat(64));
            }
            rework.put("renderCalls", 1);
            rework.put("ttsCalls", 0);
            rework.put("durationMs", 41_500L);
            return new ProductionResultVo(List.of(video), JSON.writeValueAsString(content));
        });

        String input = "{\"kind\":\"article\"}";
        ProductionRunVo run = new ProductionRunVo(90L, ProductionBizType.news_explainer, 7L, 4L, 1,
            UUID.randomUUID().toString(), input);
        HeronProductionTask task = new HeronProductionTask();
        task.setId(90L); task.setBizType(ProductionBizType.news_explainer.name()); task.setBizId(7L); task.setVersionId(4L);
        task.setOwnerId(12L); task.setActorId(12L); task.setStatus("running"); task.setAttemptNo(1);
        task.setRunToken(run.runToken()); task.setInputSnapshot(input);
        HeronProductionTask oldTask = new HeronProductionTask();
        oldTask.setId(80L); oldTask.setBizType(ProductionBizType.news_explainer.name()); oldTask.setBizId(7L); oldTask.setVersionId(3L);
        oldTask.setOwnerId(12L);
        HeronNewsWork work = new HeronNewsWork();
        work.setId(7L); work.setOwnerId(12L); work.setCandidateId(6L); work.setCurrentVersionId(3L); work.setActiveTaskId(90L);
        HeronNewsCandidate candidate = new HeronNewsCandidate(); candidate.setId(6L); candidate.setActiveTaskId(90L);
        HeronVideoVersion previous = version(3L, 80L, input);
        previous.setScript("{\"scenes\":[]}"); previous.setArtifactManifest("[]"); previous.setChecks("[]"); previous.setDurationMs(41_500L);
        HeronVideoVersion target = version(4L, 90L, input);
        var tasks = mock(HeronProductionTaskMapper.class);
        var works = mock(HeronNewsWorkMapper.class);
        var candidates = mock(HeronNewsCandidateMapper.class);
        var versions = mock(HeronVideoVersionMapper.class);
        var audits = mock(HeronSceneReworkMapper.class);
        var steps = mock(HeronTaskStepMapper.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(tasks.lockById(90L)).thenReturn(task); when(tasks.selectById(80L)).thenReturn(oldTask);
        when(works.lockById(7L)).thenReturn(work); when(candidates.lockById(6L)).thenReturn(candidate);
        when(versions.lockById(3L)).thenReturn(previous); when(versions.lockById(4L)).thenReturn(target);
        when(versions.lambda()).thenReturn(chain());
        when(audits.insert(any(org.dromara.heron.domain.HeronSceneRework.class))).thenReturn(1);
        when(tasks.lambda()).thenReturn(chain());
        when(works.lambda()).thenReturn(chain());
        when(candidates.lambda()).thenReturn(chain());
        when(steps.lambda()).thenReturn(chain());
        SceneReworkService service = new SceneReworkService(pipeline, mock(NewsProductionDomain.class), tasks,
            candidates, works, versions, audits, steps, artifacts, mock(ProductionNotificationService.class), transactions);
        JsonNode replacement = JSON.readTree("{\"scene_id\":\"scene_001\"}");

        try (MockedStatic<OssFactory> factory = mockStatic(OssFactory.class)) {
            factory.when(() -> OssFactory.instance("qiniu")).thenReturn(oss);
            ProductionResultVo first = service.rework(run, "scene_001", replacement);
            ProductionArtifactVo committed = first.artifacts().getFirst();
            assertTrue(Files.isRegularFile(root.resolve("data").resolve(committed.storageKey())));
            Path pendingFile = artifacts.prepareWorkDirectory(run).resolve("pending.mp4");
            Files.writeString(pendingFile, "pending-video");
            ProductionArtifactVo pending = artifacts.finalizeFile(run, "mp4", pendingFile, "video/mp4");
            target.setArtifactManifest("[]");
            assertThrows(HeronBusinessException.class, () -> service.rework(run, "scene_001", replacement));
            assertTrue(Files.isRegularFile(root.resolve("data").resolve(pending.storageKey())),
                "目标版本已有 manifest 时，失败清理必须保留字节");
            artifacts.releaseAttempt(run, true);
            target.setArtifactManifest(null);
            // 模拟真实数据库：首笔事务已把任务置为 succeeded，后来的并发迟到调用必须被归属闸拒绝。
            task.setStatus("succeeded");
            var concurrent = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                assertThrows(HeronBusinessException.class, () -> service.rework(run, "scene_001", replacement));
                return true;
            });
            var duplicate = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                assertThrows(HeronBusinessException.class, () -> service.rework(run, "scene_001", replacement));
                return true;
            });
            assertTrue(concurrent.get());
            assertTrue(duplicate.get());
            ProductionRunVo forged = new ProductionRunVo(run.taskId(), run.bizType(), run.bizId(), run.versionId(),
                run.attemptNo(), UUID.randomUUID().toString(), input);
            assertThrows(HeronBusinessException.class, () -> service.rework(forged, "scene_001", replacement));

            verify(oss, never()).delete(anyString());
            assertTrue(Files.isRegularFile(root.resolve("data").resolve(committed.storageKey())));
            artifacts.verify(committed);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T chain() {
        return (T) mock(org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper.class, invocation -> {
            Class<?> returned = invocation.getMethod().getReturnType();
            if (returned == Object.class) {
                return invocation.getMock();
            }
            if (returned == int.class) {
                return 1;
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static HeronVideoVersion version(long id, long taskId, String input) {
        HeronVideoVersion version = new HeronVideoVersion();
        version.setId(id);
        version.setTaskId(taskId);
        version.setWorkId(7L);
        version.setOwnerId(12L);
        version.setInputSnapshot(input);
        return version;
    }
}
