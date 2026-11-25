package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 数据库权威上一版、行锁事务与 V64 审计边界的服务层回归。 */
@DisplayName("场景返工：数据库权威来源与事务")
class SceneReworkServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final NewsProductionPipeline pipeline = mock(NewsProductionPipeline.class);
    private final NewsProductionDomain domain = mock(NewsProductionDomain.class);
    private final HeronProductionTaskMapper tasks = mock(HeronProductionTaskMapper.class);
    private final HeronNewsWorkMapper works = mock(HeronNewsWorkMapper.class);
    private final HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
    private final HeronVideoVersionMapper versions = mock(HeronVideoVersionMapper.class);
    private final HeronSceneReworkMapper audits = mock(HeronSceneReworkMapper.class);
    private final HeronTaskStepMapper steps = mock(HeronTaskStepMapper.class);
    private final ProductionArtifactService artifacts = mock(ProductionArtifactService.class);
    private final ProductionNotificationService notifications = mock(ProductionNotificationService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private SceneReworkService service;
    private LambdaCrudChainWrapper<HeronVideoVersion, org.dromara.heron.domain.vo.NewsVideoVersionVo> versionUpdate;
    private ProductionRunVo run;
    private HeronProductionTask task;
    private HeronVideoVersion previous;
    private HeronVideoVersion target;
    private List<ProductionArtifactVo> previousArtifacts;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        versionUpdate = chain(LambdaCrudChainWrapper.class);
        LambdaCrudChainWrapper<HeronNewsWork, org.dromara.heron.domain.vo.NewsWorkVo> workUpdate =
            chain(LambdaCrudChainWrapper.class);
        LambdaCrudChainWrapper<HeronNewsCandidate, org.dromara.heron.domain.vo.NewsCandidateVo> candidateUpdate =
            chain(LambdaCrudChainWrapper.class);
        LambdaCrudChainWrapper<HeronProductionTask, org.dromara.heron.domain.vo.ProductionTaskVo> taskUpdate =
            chain(LambdaCrudChainWrapper.class);
        LambdaCrudChainWrapper<HeronTaskStep, org.dromara.heron.domain.vo.ProductionStepVo> stepUpdate =
            chain(LambdaCrudChainWrapper.class);
        when(versions.lambda()).thenReturn(versionUpdate);
        when(works.lambda()).thenReturn(workUpdate);
        when(candidates.lambda()).thenReturn(candidateUpdate);
        when(tasks.lambda()).thenReturn(taskUpdate);
        when(steps.lambda()).thenReturn(stepUpdate);
        when(versionUpdate.updateCount()).thenReturn(1);
        when(workUpdate.updateCount()).thenReturn(1);
        when(audits.insert(any(HeronSceneRework.class))).thenReturn(1);
        service = new SceneReworkService(pipeline, domain, tasks, candidates, works, versions, audits, steps,
            artifacts, notifications, transactions);

        String input = "{\"kind\":\"article\"}";
        run = new ProductionRunVo(90L, ProductionBizType.news_explainer, 7L, 4L, 1,
            UUID.randomUUID().toString(), input);
        task = new HeronProductionTask();
        task.setId(90L);
        task.setBizType(ProductionBizType.news_explainer.name());
        task.setBizId(7L);
        task.setVersionId(4L);
        task.setOwnerId(12L);
        task.setActorId(12L);
        task.setStatus(ProductionStatus.running.name());
        task.setAttemptNo(1);
        task.setRunToken(run.runToken());
        task.setInputSnapshot(input);
        HeronProductionTask oldTask = new HeronProductionTask();
        oldTask.setId(80L);
        oldTask.setBizType(ProductionBizType.news_explainer.name());
        oldTask.setBizId(7L);
        oldTask.setVersionId(3L);
        oldTask.setOwnerId(12L);
        oldTask.setAttemptNo(1);
        oldTask.setRunToken(UUID.randomUUID().toString());
        oldTask.setInputSnapshot(input);
        HeronNewsWork work = new HeronNewsWork();
        work.setId(7L);
        work.setOwnerId(12L);
        work.setCandidateId(6L);
        work.setCurrentVersionId(3L);
        work.setActiveTaskId(90L);
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(6L);
        candidate.setActiveTaskId(90L);
        previous = version(3L, 80L, input);
        previous.setScript("{\"title\":\"数据库权威标题\",\"scenes\":[]}");
        previous.setArtifactManifest("[]");
        previous.setChecks("[]");
        previous.setDurationMs(41_500L);
        previous.setContentProfile("{\"hookType\":\"数字反差\",\"openingStructure\":\"contrast\"}");
        previous.setQualityReport("{\"previous\":true}");
        target = version(4L, 90L, input);
        when(tasks.lockById(90L)).thenReturn(task);
        when(tasks.selectById(80L)).thenReturn(oldTask);
        when(works.lockById(7L)).thenReturn(work);
        when(candidates.lockById(6L)).thenReturn(candidate);
        when(versions.lockById(3L)).thenReturn(previous);
        when(versions.lockById(4L)).thenReturn(target);
    }

    @Test
    @DisplayName("只接受执行中的 trusted run，上一版 ID/manifest/script 全部来自锁定数据库记录")
    void usesDatabaseAuthoritativePreviousVersion() {
        previousArtifacts = List.of(new ProductionArtifactVo(UUID.randomUUID().toString(), "script",
            "videos/7/v3/a1-" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".json",
            10, "a".repeat(64), "application/json"));
        String selections = "[{\"sceneId\":\"scene_003\",\"sceneNumber\":1,\"status\":\"SELECTED\","
            + "\"imageValue\":\"scene3.png\",\"gate\":{\"inputLocator\":\"scene:scene_003\","
            + "\"measured\":{\"licenseChecked\":true,\"fallbackToText\":false}}}]";
        byte[] assetManifestBytes = ("{\"mediaSelections\":" + selections + "}")
            .getBytes(StandardCharsets.UTF_8);
        ProductionArtifactVo assetManifest = new ProductionArtifactVo(UUID.randomUUID().toString(), "asset_manifest",
            "videos/7/v3/a1-" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".json",
            assetManifestBytes.length, DigestUtil.sha256Hex(assetManifestBytes), "application/json");
        previousArtifacts = List.of(previousArtifacts.getFirst(), assetManifest);
        when(artifacts.openVerified(assetManifest)).thenReturn(new java.io.ByteArrayInputStream(assetManifestBytes));
        previous.setArtifactManifest(JSON.writeValueAsString(previousArtifacts));
        ProductionResultVo generated = result();
        when(pipeline.reworkScene(eq(run), any())).thenReturn(generated);
        JsonNode replacement = JSON.readTree("{\"scene_id\":\"scene_001\"}");

        ProductionResultVo actual = service.rework(run, "scene_001", replacement);

        assertEquals(generated, actual);
        ArgumentCaptor<SceneReworkRequest> captor = ArgumentCaptor.forClass(SceneReworkRequest.class);
        verify(pipeline).reworkScene(eq(run), captor.capture());
        SceneReworkRequest request = captor.getValue();
        assertEquals(3L, request.previousVersionId());
        assertEquals(DigestUtil.sha256Hex(JSON.writeValueAsString(previousArtifacts)),
            request.expectedManifestSha256());
        assertEquals(previousArtifacts, request.previousArtifacts());
        assertEquals(JSON.readTree(previous.getScript()),
            JSON.readTree(request.previousResultJson()).path("script"));
        assertEquals("数字反差", JSON.readTree(request.previousResultJson()).path("contentProfile").path("hookType").asString());
        var setters = org.mockito.Mockito.mockingDetails(versionUpdate).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("set"))
            .map(call -> call.getArguments()[1]).toList();
        assertTrue(setters.contains("{\"regenerated\":true}"));
        assertTrue(setters.contains("{\"hookType\":\"数字反差\"}"));
        assertTrue(setters.contains(41_500L));
        verify(versions).lockById(3L);
        verify(versions).lockById(4L);
        verify(audits).insert(any(HeronSceneRework.class));
        verify(transactions).commit(any());
        verify(artifacts).releaseAttempt(run, true);
        verify(artifacts, never()).releaseAttempt(any(), eq(false));
    }

    @Test
    @DisplayName("审计或版本写入失败时回滚事务并回收本次产物")
    void rollsBackAndReleasesWhenAuditFails() {
        previous.setArtifactManifest("[]");
        when(pipeline.reworkScene(eq(run), any())).thenReturn(result());
        when(audits.insert(any(HeronSceneRework.class))).thenReturn(0);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.rework(run, "scene_001", JSON.readTree("{\"scene_id\":\"scene_001\"}")));

        assertEquals("news_scene_rework_audit_failed", error.getCode());
        verify(transactions).rollback(any());
        verify(artifacts).releaseAttempt(eq(run), eq(false), any());
    }

    @Test
    @DisplayName("客户端伪造的 runToken 或非 running 任务不能进入渲染")
    void rejectsUntrustedRunBeforeRender() {
        ProductionRunVo forged = new ProductionRunVo(90L, ProductionBizType.news_explainer, 7L, 4L, 1,
            UUID.randomUUID().toString(), run.inputJson());

        assertThrows(HeronBusinessException.class,
            () -> service.rework(forged, "scene_001", JSON.readTree("{\"scene_id\":\"scene_001\"}")));

        verify(pipeline, never()).reworkScene(any(), any());
        verify(transactions).rollback(any());
    }

    @SuppressWarnings("unchecked")
    private static <T> T chain(Class<T> type) {
        return mock(type, invocation -> {
            Class<?> returned = invocation.getMethod().getReturnType();
            if (returned == Object.class) {
                return invocation.getMock();
            }
            if (returned == int.class) {
                return 1;
            }
            if (returned == boolean.class) {
                return true;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private ProductionResultVo result() {
        ObjectNode content = JSON.createObjectNode();
        content.put("sourceVersionId", 4L);
        content.put("artifactManifestSha256", DigestUtil.sha256Hex("[]"));
        content.put("durationMs", 41_500L);
        content.set("script", JSON.readTree("{\"scenes\":[]}"));
        content.set("checks", JSON.createArrayNode());
        content.putObject("qualityReport").put("regenerated", true);
        content.putObject("contentProfile").put("hookType", "数字反差");
        ObjectNode rework = content.putObject("sceneRework");
        rework.put("previousVersionId", 3L);
        rework.put("previousManifestSha256", DigestUtil.sha256Hex(previous.getArtifactManifest()));
        rework.put("previousScriptSha256", DigestUtil.sha256Hex(previous.getScript()));
        rework.put("sceneId", "scene_001");
        rework.put("sceneIndex", 0);
        rework.put("startMs", 0L);
        rework.put("endMs", 10_000L);
        rework.put("previousVideoSha256", "a".repeat(64));
        rework.put("sceneVideoSha256", "b".repeat(64));
        rework.put("videoSha256", "c".repeat(64));
        rework.put("contactSheetSha256", "d".repeat(64));
        rework.put("scriptSha256", "e".repeat(64));
        rework.put("audioSha256", "f".repeat(64));
        rework.put("renderCalls", 1);
        rework.put("ttsCalls", 0);
        rework.put("durationMs", 41_500L);
        return new ProductionResultVo(List.of(), JSON.writeValueAsString(content));
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
