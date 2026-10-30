package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NewsWorkTransactionTest extends NewsMysqlTestSupport {
    @Test
    void claimRaceHasOneOwnerAndSameActorReplayReturnsSameWork() throws Exception {
        for (int round = 0; round < 20; round++) {
            long candidate = news.addManual(manual("认领竞争" + UUID.randomUUID(), "来源")).getCandidateId();
            CyclicBarrier start = new CyclicBarrier(2);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> claim(start, candidate, OWNER));
                var b = pool.submit(() -> claim(start, candidate, OTHER));
                Object first = a.get(10, TimeUnit.SECONDS);
                Object second = b.get(10, TimeUnit.SECONDS);
                assertEquals(1, List.of(first, second).stream().filter(NewsWorkVo.class::isInstance).count());
                NewsWorkVo winner = (NewsWorkVo) (first instanceof NewsWorkVo ? first : second);
                assertEquals(409, ((HeronBusinessException) (first instanceof HeronBusinessException ? first : second)).getStatus());
                loginAs(winner.getOwnerId());
                assertEquals(winner.getWorkId(), news.claim(candidate).getWorkId());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM heron_news_work WHERE candidate_id=?", Integer.class, candidate));
            }
        }
    }

    private Object claim(CyclicBarrier start, long id, long actor) throws Exception {
        loginAs(actor);
        start.await(5, TimeUnit.SECONDS);
        try { return news.claim(id); } catch (HeronBusinessException e) { return e; }
    }

    @Test
    void manualDedupNormalizesPunctuationButPreservesNumericAnchorsAndPollingHeat() {
        var first = news.addManual(manual("某地发布２０２６年 新规！", "事实"));
        var repeated = news.addManual(manual("某地发布2026年新规", "事实"));
        assertEquals(first.getCandidateId(), repeated.getCandidateId());
        assertEquals(1L, repeated.getHitCount());
        assertNotEquals(first.getCandidateId(), news.addManual(manual("某地发布2027年新规", "事实")).getCandidateId());
        var decimal = news.addManual(manual("某市产值增长1.2亿元", "事实"));
        assertNotEquals(decimal.getCandidateId(), news.addManual(manual("某市产值增长12亿元", "事实")).getCandidateId());
        var merged = news.addManual(manual("某地发布2026年新规", "另一份证据"));
        assertEquals(2L, merged.getHitCount());
    }

    @Test
    void revisionAndMissingPipelineLeaveNoReservationAndReplayKeepsFrozenSnapshot() {
        NewsWorkVo work = work();
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> news.generate(work.getWorkId(), new NewsGenerateBo("stale", 0L))).getStatus());
        assertEquals(503, assertThrows(HeronBusinessException.class,
            () -> news.generate(work.getWorkId(), new NewsGenerateBo("missing", 1L))).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM heron_video_version", Integer.class));
        assertNull(jdbc.queryForObject("SELECT active_task_id FROM heron_news_work WHERE work_id=?", Long.class, work.getWorkId()));
        allowProduction();
        var first = news.generate(work.getWorkId(), new NewsGenerateBo("one", 1L));
        String snapshot = jdbc.queryForObject("SELECT input_snapshot FROM heron_video_version WHERE version_id=?", String.class, first.versionId());
        var edited = news.saveDraft(work.getWorkId(), new NewsDraftBo(1L, "新草稿", "编辑后的正文", "default", "default"));
        assertEquals(2L, edited.getRevision());
        assertEquals(first.taskId(), news.generate(work.getWorkId(), new NewsGenerateBo("one", 1L)).taskId());
        assertEquals(snapshot, jdbc.queryForObject("SELECT input_snapshot FROM heron_video_version WHERE version_id=?", String.class, first.versionId()));
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> news.generate(work.getWorkId(), new NewsGenerateBo("two", 2L))).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM heron_video_version", Integer.class));
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> news.saveDraft(work.getWorkId(), new NewsDraftBo(1L, "旧", "正文", "default", "default"))).getStatus());
    }

    @Test
    void publishedCandidateCannotProduceAndCompleteRejectsIncompleteManifest() throws Exception {
        var work = work();
        allowProduction();
        jdbc.update("UPDATE heron_news_candidate SET published_at=UTC_TIMESTAMP(3) WHERE candidate_id=?", work.getCandidateId());
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> news.generate(work.getWorkId(), new NewsGenerateBo("published", 1L))).getStatus());
        jdbc.update("UPDATE heron_news_candidate SET published_at=NULL WHERE candidate_id=?", work.getCandidateId());
        var receipt = news.generate(work.getWorkId(), new NewsGenerateBo("valid", 1L));
        var run = execution.claim(ProductionBizType.news_video).orElseThrow();
        var files = fixtureFiles(run);
        assertThrows(HeronBusinessException.class,
            () -> execution.succeed(run, new ProductionResultVo(files.subList(0, 1), "{}")));
        assertEquals("running", jdbc.queryForObject("SELECT status FROM heron_production_task WHERE task_id=?", String.class, receipt.taskId()));
        assertNull(jdbc.queryForObject("SELECT artifact_manifest FROM heron_video_version WHERE version_id=?", String.class, receipt.versionId()));
    }

    @Test
    void approvalCannotMoveToNextVersionAndRepeatedDecisionKeepsActorAndTime() throws Exception {
        var first = completed();
        var approved = news.review(first.workId(), first.versionId(), first.approve());
        grantPermissions(OTHER, "heron:newsWork:all", "heron:newsWork:review");
        loginAs(OTHER);
        var replay = news.review(first.workId(), first.versionId(), first.approve());
        assertEquals(approved.getReviewBy(), replay.getReviewBy());
        assertEquals(approved.getReviewAt(), replay.getReviewAt());
        loginAs(OWNER);
        var second = completed(news.queryById(first.workId()));
        assertEquals("pending", news.version(second.workId(), second.versionId()).getReviewStatus());
        assertEquals(409, assertThrows(HeronBusinessException.class,
            () -> news.review(second.workId(), second.versionId(), first.approve())).getStatus());
    }

    @Test
    void concurrentOppositeReviewsHaveOnlyOneWinner() throws Exception {
        for (int round = 0; round < 20; round++) {
            loginAs(OWNER);
            var fixture = completed();
            CyclicBarrier start = new CyclicBarrier(2);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> review(start, fixture, "approved"));
                var b = pool.submit(() -> review(start, fixture, "rejected"));
                assertEquals(1, List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)).stream().filter(Boolean::booleanValue).count());
            }
        }
    }

    @Test
    void evidenceIsBoundedAndPreservesFirstAndLatest() {
        Long id = null;
        for (int i = 0; i < 110; i++) id = news.addManual(manual("有界证据候选", "证据" + i)).getCandidateId();
        var candidate = news.candidate(id);
        var evidence = org.dromara.common.json.utils.JsonUtils.getJsonMapper().readTree(candidate.getEvidence());
        assertEquals(100, evidence.path("items").size());
        assertTrue(evidence.path("evidenceTruncated").asBoolean());
        assertEquals("证据0", evidence.path("items").get(0).path("summary").asText());
        assertEquals("证据109", evidence.path("items").get(99).path("summary").asText());
        assertEquals(110L, candidate.getHitCount());
    }

    @Test
    void successfulVersionCannotBeOverwrittenAndBadGatesNeverApprove() throws Exception {
        var f = completed();
        String original = jdbc.queryForObject("SELECT artifact_manifest FROM heron_video_version WHERE version_id=?", String.class, f.versionId());
        var task = context.getBean(org.dromara.heron.mapper.HeronProductionTaskMapper.class)
            .selectById(news.version(f.workId(), f.versionId()).getTaskId());
        var late = new ProductionRunVo(task.getTaskId(), ProductionBizType.news_video, task.getBizId(), task.getVersionId(),
            task.getAttemptNo(), task.getRunToken(), task.getInputSnapshot(), null);
        assertFalse(execution.succeed(late, new ProductionResultVo(List.of(), "{}")));
        assertEquals(original, jdbc.queryForObject("SELECT artifact_manifest FROM heron_video_version WHERE version_id=?", String.class, f.versionId()));
        jdbc.update("UPDATE heron_video_version SET checks='[]' WHERE version_id=?", f.versionId());
        assertEquals(409, assertThrows(HeronBusinessException.class, () -> news.review(f.workId(), f.versionId(), f.approve())).getStatus());
        assertEquals("pending", jdbc.queryForObject("SELECT review_status FROM heron_video_version WHERE version_id=?", String.class, f.versionId()));
        jdbc.update("UPDATE heron_video_version SET checks=?,script='{}' WHERE version_id=?", checksJson(), f.versionId());
        assertEquals(409, assertThrows(HeronBusinessException.class, () -> news.review(f.workId(), f.versionId(), f.approve())).getStatus());
    }

    @Test
    void staleReleaseDoesNotClearNewerTaskPointers() {
        var work = work();
        allowProduction();
        var receipt = news.generate(work.getWorkId(), new NewsGenerateBo("active", 1L));
        var stale = context.getBean(org.dromara.heron.mapper.HeronProductionTaskMapper.class).selectById(receipt.taskId());
        stale.setTaskId(receipt.taskId() - 1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> {
            context.getBean(org.dromara.heron.mapper.HeronProductionLaneMapper.class).lockByType(ProductionBizType.news_video);
            var domain = context.getBean("newsProductionDomain", IProductionDomain.class);
            domain.lockObject(work.getWorkId());
            domain.release(stale);
        });
        assertEquals(receipt.taskId(), jdbc.queryForObject("SELECT active_task_id FROM heron_news_work WHERE work_id=?", Long.class, work.getWorkId()));
        assertEquals(receipt.taskId(), jdbc.queryForObject("SELECT active_task_id FROM heron_news_candidate WHERE candidate_id=?", Long.class, work.getCandidateId()));
    }

    private boolean review(CyclicBarrier start, Completed f, String decision) throws Exception {
        loginAs(OWNER);
        start.await(5, TimeUnit.SECONDS);
        try {
            news.review(f.workId(), f.versionId(), new NewsReviewBo(decision, f.file("contact_sheet").sha256(), f.file("mp4").sha256(), "人工决议"));
            return true;
        } catch (HeronBusinessException e) { assertEquals(409, e.getStatus()); return false; }
    }
}
