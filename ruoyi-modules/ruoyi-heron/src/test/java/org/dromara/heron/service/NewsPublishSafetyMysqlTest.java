package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.domain.bo.NewsPublishBo;
import org.dromara.heron.support.NewsMysqlTestConfiguration;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ContextConfiguration(classes = {NewsMysqlTestConfiguration.class, NewsPublishSafetyMysqlTest.AdapterConfiguration.class},
    inheritLocations = false)
class NewsPublishSafetyMysqlTest extends NewsMysqlTestSupport {
    @Autowired RecordingAdapter adapter;

    @BeforeEach void resetAdapter() {
        adapter.calls.set(0);
        adapter.fail = false;
        adapter.badReceipt = false;
        adapter.entered = new CountDownLatch(1);
        adapter.release = new CountDownLatch(0);
    }

    @Test
    void failedUploadPersistsAnUnknownAttemptAndApprovalButNeverRetries() throws Exception {
        var version = completed();
        adapter.fail = true;
        assertEquals("news_publish_unknown", assertThrows(HeronBusinessException.class,
            () -> news.confirm(version.workId(), version.versionId(), version.approve())).getCode());
        var replay = news.confirm(version.workId(), version.versionId(), version.approve());
        assertEquals("approved", replay.getReviewStatus());
        assertEquals("unknown", replay.getPublishAttempt().getStatus());
        assertNull(replay.getPublishAt());
        assertEquals(1, adapter.calls.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM heron_news_publish_attempt WHERE work_id=?", Integer.class, version.workId()));
    }

    @Test
    void failedReceiptTransactionCannotEraseTheCommittedAttempt() throws Exception {
        var version = completed();
        adapter.badReceipt = true;
        assertThrows(HeronBusinessException.class, () -> news.confirm(version.workId(), version.versionId(), version.approve()));
        var replay = news.version(version.workId(), version.versionId());
        assertEquals("unknown", replay.getPublishAttempt().getStatus());
        assertNull(replay.getPublishAt());
        assertNull(jdbc.queryForObject("SELECT published_at FROM heron_news_candidate WHERE candidate_id=(SELECT candidate_id FROM heron_news_work WHERE work_id=?)",
            LocalDateTime.class, version.workId()));
        assertEquals(1, adapter.calls.get());
    }

    @Test
    void receiptDatabaseFailureRollsBackPublishedFactsButKeepsTheAttempt() throws Exception {
        var version = completed();
        jdbc.execute("CREATE TRIGGER heron_it_reject_publish BEFORE UPDATE ON heron_news_candidate FOR EACH ROW BEGIN IF NEW.published_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rejects receipt'; END IF; END");
        try {
            assertEquals("news_publish_unknown", assertThrows(HeronBusinessException.class,
                () -> news.confirm(version.workId(), version.versionId(), version.approve())).getCode());
            var current = news.version(version.workId(), version.versionId());
            assertNull(current.getPublishAt(), "版本发布事实应随失败的回执事务回滚");
            assertEquals("unknown", current.getPublishAttempt().getStatus());
            assertEquals(1, adapter.calls.get());
        } finally {
            jdbc.execute("DROP TRIGGER heron_it_reject_publish");
        }
    }

    @Test
    void concurrentConfirmationAndAnotherVersionCannotUploadDuringAnUnresolvedAttempt() throws Exception {
        var work = work();
        var first = completed(work);
        var second = completed(work);
        adapter.release = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var uploading = workers.submit(() -> maintenance.confirmForAuto(first.workId(), first.versionId(), OWNER));
            assertTrue(adapter.entered.await(10, TimeUnit.SECONDS));
            var repeated = news.confirm(first.workId(), first.versionId(), first.approve());
            assertEquals("started", repeated.getPublishAttempt().getStatus());
            assertThrows(HeronBusinessException.class, () -> news.confirm(second.workId(), second.versionId(), second.approve()));
            assertEquals(1, adapter.calls.get());
            adapter.release.countDown();
            assertEquals("published", uploading.get(10, TimeUnit.SECONDS).getPublishAttempt().getStatus());
        } finally {
            adapter.release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void anAlreadyPublishedCandidateIsRejectedBeforeTheAdapterSeesAnotherVersion() throws Exception {
        var work = work();
        var first = completed(work);
        var second = completed(work);
        news.review(first.workId(), first.versionId(), first.approve());
        news.publish(first.workId(), first.versionId(), new NewsPublishBo("https://www.douyin.com/video/7400000000000000000",
            "heron", LocalDateTime.now(clock).minusMinutes(1), first.file("mp4").sha256()));
        assertThrows(HeronBusinessException.class, () -> news.confirm(second.workId(), second.versionId(), second.approve()));
        assertEquals(0, adapter.calls.get());
    }

    @Test
    void uploadTitleComesFromTheFrozenVideoScript() throws Exception {
        var version = completed();
        String expected = org.dromara.common.json.utils.JsonUtils.getJsonMapper()
            .readTree(news.version(version.workId(), version.versionId()).getScript()).path("title").asString();
        jdbc.update("UPDATE heron_news_work SET title=? WHERE work_id=?", "后续编辑的新标题", version.workId());
        news.confirm(version.workId(), version.versionId(), version.approve());
        assertEquals(expected, adapter.lastTitle);
    }

    @Test
    void databaseAllowsHistoricalAttemptsButOnlyOneActiveAttemptPerCandidate() throws Exception {
        var version = completed();
        Long candidate = jdbc.queryForObject("SELECT candidate_id FROM heron_news_work WHERE work_id=?", Long.class, version.workId());
        insertAttempt(1, candidate, version.workId(), version.versionId(), "unknown");
        assertThrows(DataIntegrityViolationException.class,
            () -> insertAttempt(2, candidate, version.workId(), version.versionId(), "started"));
        insertAttempt(3, candidate, version.workId(), version.versionId(), "not_published");
        jdbc.update("UPDATE heron_news_publish_attempt SET status='not_published' WHERE attempt_id=1");
        insertAttempt(2, candidate, version.workId(), version.versionId(), "started");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM heron_news_publish_attempt", Integer.class));
    }

    private void insertAttempt(long id, long candidate, long work, long version, String status) {
        jdbc.update("INSERT INTO heron_news_publish_attempt(attempt_id,candidate_id,work_id,version_id,video_sha,source,status,started_at,updated_at,actor_id) VALUES(?,?,?,?,?,'sandbox',?,?,?,?)",
            id, candidate, work, version, "a".repeat(64), status, LocalDateTime.now(clock), LocalDateTime.now(clock), OWNER);
    }

    @TestConfiguration
    static class AdapterConfiguration {
        @Bean @Primary RecordingAdapter recordingAdapter() { return new RecordingAdapter(); }
    }

    static class RecordingAdapter implements IPublishAdapter {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        volatile boolean badReceipt;
        volatile String lastTitle;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        @Override public String source() { return "sandbox"; }
        @Override public PublishReceipt publish(PublishRequest request) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet();
            lastTitle = request.title();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test upload did not finish");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            if (fail) throw new HeronBusinessException(504, "publish_timeout", "simulated timeout");
            return new PublishReceipt("sandbox://news/" + request.versionId(), "sandbox", request.requestedAt(), badReceipt ? "wrong" : source());
        }
    }
}
