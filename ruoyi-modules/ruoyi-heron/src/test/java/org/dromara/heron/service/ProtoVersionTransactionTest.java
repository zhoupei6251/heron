package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProtoGenerateBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.service.impl.ProtoProductionDomain;
import org.dromara.heron.support.ProtoMysqlTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL tests; fixture bytes are transaction evidence, never Axure compatibility evidence. */
class ProtoVersionTransactionTest extends ProtoMysqlTestSupport {

    @Test
    void normalizesUnicodeAndCountsCodePointsNotUtf16Units() {
        for (String empty : List.of("", " \r\n\u00a0\u200b\ufeff\u200d")) {
            assertEquals(400, assertThrows(HeronBusinessException.class,
                () -> proto.insertByBo(draft(empty))).getStatus());
        }
        var bo = draft("\ufeff\u200be\u0301\r\n内  容\u00a0");
        bo.setName("\u200b 项目 \ufeff");
        var project = proto.insertByBo(bo);
        assertEquals("é\n内  容", project.getRequirement());
        assertEquals("项目", project.getName());
        assertEquals(8000, proto.insertByBo(draft("\ud83d\ude00".repeat(8000)))
            .getRequirement().codePointCount(0, 16000));
        assertThrows(HeronBusinessException.class, () -> proto.insertByBo(draft("界".repeat(8001))));
        bo.setName("名".repeat(101));
        assertThrows(HeronBusinessException.class, () -> proto.insertByBo(bo));
        bo.setName("名".repeat(100));
        assertEquals(100, proto.insertByBo(bo).getName().length());
        bo.setCanvas("desktop");
        assertThrows(HeronBusinessException.class, () -> proto.insertByBo(bo));
    }

    @Test
    void replaySurvivesLaterEditingButDifferentRevisionConflicts() {
        var p = proto.insertByBo(draft("第一版"));
        var first = proto.generate(p.getProjectId(), new ProtoGenerateBo("same", 1));
        var edit = draft("第二版");
        edit.setExpectedRevision(1L);
        assertEquals(2, proto.updateByBo(p.getProjectId(), edit).getRevision());
        assertEquals(first, proto.generate(p.getProjectId(), new ProtoGenerateBo("same", 1)));
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> proto.generate(p.getProjectId(), new ProtoGenerateBo("same", 2))).getStatus());
        var frozen = proto.version(p.getProjectId(), first.versionId());
        assertEquals("第一版", frozen.getInput().requirement());
        assertEquals(1, frozen.getInput().revision());
        assertThrows(HeronBusinessException.class, () -> proto.archive(p.getProjectId()));
        production.cancel(first.taskId());
        var second = proto.generate(p.getProjectId(), new ProtoGenerateBo("next", 2));
        assertEquals(2, proto.version(p.getProjectId(), second.versionId()).getVersionNo());
        assertEquals("第一版", proto.version(p.getProjectId(), first.versionId()).getInput().requirement());
        assertEquals("cancelled", proto.version(p.getProjectId(), first.versionId()).getStatus());
    }

    @Test
    void concurrentFourProjectsReserveExactlyThreeVersions() throws Exception {
        var ids = List.of(createProject(), createProject(), createProject(), createProject());
        CyclicBarrier barrier = new CyclicBarrier(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = ids.stream().map(id -> pool.submit((Callable<Boolean>) () -> {
                loginOwner();
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    proto.generate(id, new ProtoGenerateBo("capacity", 1));
                    return true;
                } catch (HeronBusinessException e) {
                    assertEquals("production_capacity_full", e.getCode());
                    return false;
                }
            })).toList();
            int admitted = 0;
            for (var future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) admitted++;
            }
            assertEquals(3, admitted);
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM heron_proto_version", Integer.class));
        assertEquals(3, jdbc.queryForObject(
            "SELECT COUNT(*) FROM heron_proto_project WHERE active_task_id IS NOT NULL", Integer.class));
    }

    @Test
    void sameProjectRaceHasOneReservationAndReleaseCannotClearAnotherTask() throws Exception {
        long id = createProject();
        var results = race(() -> proto.generate(id, new ProtoGenerateBo("a", 1)),
            () -> proto.generate(id, new ProtoGenerateBo("b", 1)));
        assertEquals(1, results.stream().filter(ProductionReceiptVo.class::isInstance).count());
        var receipt = (ProductionReceiptVo) results.stream().filter(ProductionReceiptVo.class::isInstance)
            .findFirst().orElseThrow();
        var task = taskMapper.selectById(receipt.taskId());
        task.setTaskId(task.getTaskId() + 1);
        tx.executeWithoutResult(ignored -> { domain.lockObject(id); domain.release(task); });
        assertEquals(receipt.taskId(), proto.queryById(id).getActiveTaskId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM heron_proto_version", Integer.class));
    }

    @Test
    void missingEngineOrPipelineDoesNotReserveAnything() {
        var empty = new StaticListableBeanFactory();
        var unavailable = new ProtoProductionDomain(projectMapper, versionMapper, artifacts, properties,
            empty.getBeanProvider(ProtoProductionDomain.CapabilitySnapshot.class),
            empty.getBeanProvider(IProductionPipeline.class));
        long id = createProject();
        var error = assertThrows(HeronBusinessException.class, () -> tx.executeWithoutResult(ignored -> {
            unavailable.lockObject(id);
            unavailable.reserve(new org.dromara.heron.domain.bo.ProductionSubmitBo(
                ProductionBizType.prototype, id, "unavailable", 1), OWNER, 900L);
        }));
        assertEquals(503, error.getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM heron_proto_version", Integer.class));
        assertNull(proto.queryById(id).getActiveTaskId());
    }

    @Test
    void unicodeNextLineWhitespaceAndNullArchiveFilterAreRejectedSafely() {
        assertEquals(400, assertThrows(HeronBusinessException.class,
            () -> proto.insertByBo(draft("\u0085\u200b"))).getStatus());
        var query = new org.dromara.heron.domain.bo.ProtoProjectQuery();
        query.setArchived(null);
        assertEquals(400, assertThrows(HeronBusinessException.class,
            () -> proto.queryPageList(query, new org.dromara.common.mybatis.core.page.PageQuery())).getStatus());
    }

    @Test
    void fileLimitIsInclusiveAndZeroOversizeOrWrongHashCannotPublish() throws Exception {
        var f = runningFixture();
        var path = properties.getDataRoot().resolve(f.rp().storageKey());
        java.nio.file.Files.write(path, new byte[0]);
        var empty = replacementRp(f);
        assertThrows(HeronBusinessException.class, () -> execution.succeed(f.run(), empty));
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(50L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        var oversized = replacementRp(f);
        assertThrows(HeronBusinessException.class, () -> execution.succeed(f.run(), oversized));
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
            channel.truncate(50L * 1024 * 1024);
        }
        var exact = replacementRp(f);
        var corrupted = exact.artifacts().stream().map(a -> a.kind().equals("native_rp")
            ? new org.dromara.heron.domain.vo.ProductionArtifactVo(a.artifactId(), a.kind(), a.storageKey(),
                a.bytes(), "0".repeat(64), a.mediaType()) : a).toList();
        assertThrows(HeronBusinessException.class, () -> execution.succeed(f.run(),
            new org.dromara.heron.domain.vo.ProductionResultVo(corrupted, exact.resultJson())));
        assertTrue(execution.succeed(f.run(), exact));
    }

    private org.dromara.heron.domain.vo.ProductionResultVo replacementRp(ProtoFixture f) throws Exception {
        var path = properties.getDataRoot().resolve(f.rp().storageKey());
        String hash;
        try (var input = java.nio.file.Files.newInputStream(path)) {
            hash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(input);
        }
        var rp = new org.dromara.heron.domain.vo.ProductionArtifactVo(f.rp().artifactId(), f.rp().kind(),
            f.rp().storageKey(), java.nio.file.Files.size(path), hash, f.rp().mediaType());
        return new org.dromara.heron.domain.vo.ProductionResultVo(f.result().artifacts().stream()
            .map(a -> a.kind().equals("native_rp") ? rp : a).toList(), f.result().resultJson());
    }

    @Test
    void completeRejectsMissingAuditAndPublishesOnlyOnce() throws Exception {
        var f = runningFixture();
        var missing = new org.dromara.heron.domain.vo.ProductionResultVo(f.result().artifacts(), "{}");
        assertThrows(HeronBusinessException.class, () -> execution.succeed(f.run(), missing));
        for (String criterion : List.of("colorPassed", "fontPassed", "semanticNamePassed", "groupingPassed")) {
            var evidence = (tools.jackson.databind.node.ObjectNode) JSON.readTree(f.result().resultJson());
            ((tools.jackson.databind.node.ObjectNode) evidence.get("audit")).put(criterion, false);
            var failedAudit = new org.dromara.heron.domain.vo.ProductionResultVo(f.result().artifacts(), JSON.writeValueAsString(evidence));
            assertThrows(HeronBusinessException.class, () -> execution.succeed(f.run(), failedAudit));
        }
        var missingPage = f.result().artifacts().stream().filter(a -> !a.kind().equals("page_preview")).toList();
        assertThrows(HeronBusinessException.class, () -> execution.succeed(f.run(),
            new org.dromara.heron.domain.vo.ProductionResultVo(missingPage, f.result().resultJson())));
        assertNull(proto.queryById(f.projectId()).getCurrentVersionId());
        assertNull(versionMapper.selectById(f.run().versionId()).getArtifactManifest());
        assertEquals("running", taskMapper.selectById(f.run().taskId()).getStatus());
        assertTrue(execution.succeed(f.run(), f.result()));
        assertFalse(execution.succeed(f.run(), f.result()));
        assertEquals(f.run().versionId(), proto.queryById(f.projectId()).getCurrentVersionId());
        assertNull(proto.queryById(f.projectId()).getActiveTaskId());
    }
}
