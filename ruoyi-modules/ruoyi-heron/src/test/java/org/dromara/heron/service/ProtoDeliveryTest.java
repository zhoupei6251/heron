package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProtoDeliverBo;
import org.dromara.heron.domain.bo.ProtoNativeVerificationBo;
import org.dromara.heron.domain.bo.ProtoReviewBo;
import org.dromara.heron.domain.vo.ProtoDeliveryVo;
import org.dromara.heron.domain.vo.ProtoVersionVo;
import org.dromara.heron.service.impl.ProtoProjectServiceImpl;
import org.dromara.heron.support.ProtoMysqlTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtoDeliveryTest extends ProtoMysqlTestSupport {

    @Test
    void laterNativeVerificationNeverRewritesAnyDeliveryColumn() throws Exception {
        var f = approvedFixture();
        var first = proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f));
        assertFalse(first.nativeVerifiedAtDelivery());
        var before = deliveryRow(f.run().versionId());
        var event = proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "verify"));
        assertTrue(event.passed());
        assertEquals(before, deliveryRow(f.run().versionId()));
        assertEquals(first, proto.deliver(f.projectId(), f.run().versionId(),
            new ProtoDeliverBo("another-key", f.rp().sha256(), f.summary().sha256())));
        var view = proto.version(f.projectId(), f.run().versionId());
        assertFalse(view.getDelivery().nativeVerifiedAtDelivery());
        assertTrue(view.getNativeVerifications().getLast().passed());
        assertEquals(409, assertThrows(HeronBusinessException.class, () -> proto.deliver(
            f.projectId(), f.run().versionId(), new ProtoDeliverBo("bad", "0".repeat(64),
                f.summary().sha256()))).getStatus());
    }

    @Test
    void anotherAuthorizedActorCannotRewriteFirstReviewVerificationOrDeliveryTime() throws Exception {
        var f = approvedFixture();
        var review = proto.version(f.projectId(), f.run().versionId());
        var verification = proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "actor-check"));
        var delivery = proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f));
        clock.advanceSeconds(10);
        grantPermissions(203L, "heron:proto:all", "heron:proto:review", "heron:proto:verify", "heron:proto:deliver");
        loginAs(203L);
        var replay = proto.review(f.projectId(), f.run().versionId(), reviewBo(f));
        assertEquals(review.getReviewBy(), replay.getReviewBy());
        assertEquals(review.getReviewAt(), replay.getReviewAt());
        assertEquals(verification, proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "actor-check")));
        assertEquals(delivery, proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f)));
    }

    @Test
    void explicitFiveActionsAndConfiguredTargetAreRequired() throws Exception {
        var f = approvedFixture();
        var incomplete = new ProtoNativeVerificationBo("partial", f.rp().sha256(), TARGET,
            true, false, true, true, true, "测试声明，不是 Axure 证据");
        assertFalse(proto.recordNativeVerification(f.projectId(), f.run().versionId(), incomplete).passed());
        var otherTarget = new ProtoNativeVerificationBo("unknown-target", f.rp().sha256(), "unknown",
            true, true, true, true, true, "未建立基准");
        assertFalse(proto.recordNativeVerification(f.projectId(), f.run().versionId(), otherTarget).passed());
        var unconfigured = new ProtoProjectServiceImpl(projectMapper, versionMapper, taskMapper, production,
            access, domain, artifacts, properties, clock, "");
        assertFalse(tx.execute(ignored -> unconfigured.recordNativeVerification(
            f.projectId(), f.run().versionId(), nativeBo(f, "no-baseline"))).passed());
        var passed = proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "passed"));
        var delivered = proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f));
        assertTrue(delivered.nativeVerifiedAtDelivery());
        assertEquals(passed.verificationId(), delivered.verificationId());
    }

    @Test
    void verificationKeyIsPersistentAndAuditHistoryIsBounded() throws Exception {
        var f = approvedFixture();
        var first = proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "same"));
        assertEquals(first, proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "same")));
        var different = new ProtoNativeVerificationBo("same", f.rp().sha256(), TARGET,
            true, false, true, true, true, "测试声明，不是 Axure 证据");
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> proto.recordNativeVerification(f.projectId(), f.run().versionId(), different)).getStatus());
        for (int i = 1; i < 100; i++) {
            proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "key-" + i));
        }
        assertThrows(HeronBusinessException.class,
            () -> proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "overflow")));
        assertEquals(100, proto.version(f.projectId(), f.run().versionId()).getNativeVerifications().size());
        assertEquals(first, proto.recordNativeVerification(f.projectId(), f.run().versionId(), nativeBo(f, "same")));
    }

    @Test
    void verificationJsonByteLimitRejectsRatherThanTruncates() throws Exception {
        var f = approvedFixture();
        String before = null;
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            try {
                proto.recordNativeVerification(f.projectId(), f.run().versionId(),
                    new ProtoNativeVerificationBo("large-" + i, f.rp().sha256(), TARGET,
                        false, false, false, false, false, "界".repeat(4000)));
                accepted++;
                before = jdbc.queryForObject("SELECT native_verifications FROM heron_proto_version WHERE version_id=?",
                    String.class, f.run().versionId());
            } catch (HeronBusinessException e) {
                assertEquals(409, e.getStatus());
                break;
            }
        }
        assertTrue(accepted > 1 && accepted < 100, "字节上限应先于条数上限触发");
        assertNotNull(before);
        assertTrue(before.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256 * 1024);
        assertEquals(before, jdbc.queryForObject(
            "SELECT native_verifications FROM heron_proto_version WHERE version_id=?", String.class, f.run().versionId()));
    }

    @Test
    void unreviewedOrDamagedFilesCannotBeDeliveredOrApproved() throws Exception {
        var f = succeededFixture();
        assertThrows(HeronBusinessException.class,
            () -> proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f)));
        assertThrows(HeronBusinessException.class, () -> proto.review(f.projectId(), f.run().versionId(),
            new ProtoReviewBo("rejected", f.rp().sha256(), f.summary().sha256(), "\u200b")));
        Files.writeString(properties.getDataRoot().resolve(f.rp().storageKey()), "changed");
        assertThrows(HeronBusinessException.class,
            () -> proto.review(f.projectId(), f.run().versionId(), reviewBo(f)));
        assertEquals("pending", proto.version(f.projectId(), f.run().versionId()).getReviewStatus());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void symlinkInsideDataRootCannotPassReview() throws Exception {
        var f = succeededFixture();
        var original = properties.getDataRoot().resolve(f.rp().storageKey());
        var target = original.resolveSibling(original.getFileName() + ".real");
        Files.move(original, target);
        Files.createSymbolicLink(original, target.getFileName());
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> proto.review(f.projectId(), f.run().versionId(), reviewBo(f))).getStatus());
        assertEquals("pending", proto.version(f.projectId(), f.run().versionId()).getReviewStatus());
    }

    @Test
    void reviewRaceAndDeliveryRacePreserveSingleActorTimeFact() throws Exception {
        for (int round = 0; round < 20; round++) {
            var f = succeededFixture();
            var outcomes = race(() -> proto.review(f.projectId(), f.run().versionId(), reviewBo(f)),
                () -> proto.review(f.projectId(), f.run().versionId(), new ProtoReviewBo("rejected",
                    f.rp().sha256(), f.summary().sha256(), "需调整")));
            assertEquals(1, outcomes.stream().filter(ProtoVersionVo.class::isInstance).count());
            var reviewed = proto.version(f.projectId(), f.run().versionId());
            var same = new ProtoReviewBo(reviewed.getReviewStatus(), f.rp().sha256(), f.summary().sha256(),
                reviewed.getReviewReason());
            var replay = proto.review(f.projectId(), f.run().versionId(), same);
            assertEquals(reviewed.getReviewAt(), replay.getReviewAt());
            assertEquals(reviewed.getReviewBy(), replay.getReviewBy());
            if ("rejected".equals(reviewed.getReviewStatus())) {
                assertThrows(HeronBusinessException.class,
                    () -> proto.deliver(f.projectId(), f.run().versionId(), deliverBo(f)));
            }
            var deliverable = "approved".equals(reviewed.getReviewStatus()) ? f : approvedFixture();
            List<Object> deliveries = race(() -> proto.deliver(deliverable.projectId(), deliverable.run().versionId(), deliverBo(deliverable)),
                () -> proto.deliver(deliverable.projectId(), deliverable.run().versionId(), deliverBo(deliverable)));
            assertInstanceOf(ProtoDeliveryVo.class, deliveries.getFirst());
            assertEquals(deliveries.getFirst(), deliveries.getLast());
        }
    }
}
