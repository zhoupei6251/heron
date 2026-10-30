package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.NewsGenerateBo;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class NewsRetentionTest extends NewsMysqlTestSupport {
    @Test
    void dryRunKeepAndInflightDoNotDelete() throws Exception {
        var f = completed();
        expire(f.versionId());
        var file = properties.getDataRoot().resolve(f.file("mp4").storageKey());
        assertEquals(1, maintenance.cleanupExpired(true).size());
        assertTrue(Files.exists(file));
        assertNull(jdbc.queryForObject("SELECT cleanup_started_at FROM heron_video_version WHERE version_id=?", Object.class, f.versionId()));
        Files.createFile(file.getParent().resolve(".keep"));
        assertTrue(maintenance.cleanupExpired(false).isEmpty());
        Files.delete(file.getParent().resolve(".keep"));
        news.generate(f.workId(), new NewsGenerateBo("inflight", 1L));
        assertTrue(maintenance.cleanupExpired(false).isEmpty());
        assertTrue(Files.exists(file));
    }

    @Test
    void cleanupPreservesReviewAndBlocksDownloads() throws Exception {
        var f = completed();
        var approved = news.review(f.workId(), f.versionId(), f.approve());
        expire(f.versionId());
        assertEquals(1, maintenance.cleanupExpired(false).size());
        assertFalse(Files.exists(properties.getDataRoot().resolve(f.file("mp4").storageKey())));
        var version = news.version(f.workId(), f.versionId());
        assertNotNull(version.getCleanedAt());
        assertEquals("approved", version.getReviewStatus());
        assertEquals(approved.getReviewBy(), version.getReviewBy());
        assertEquals(approved.getReviewVideoSha(), version.getReviewVideoSha());
        assertTrue(version.getArtifacts().stream().noneMatch(a -> a.available()));
        assertEquals(410, assertThrows(HeronBusinessException.class, () -> news.download(f.workId(), f.versionId(),
            f.file("mp4").artifactId(), f.file("mp4").sha256())).getStatus());
    }

    @Test
    void partialDeletionFailureIsNotCleanedAndCanResume() throws Exception {
        var f = completed();
        expire(f.versionId());
        var corrupt = properties.getDataRoot().resolve(f.file("asset_manifest").storageKey());
        byte[] original = Files.readAllBytes(corrupt);
        Files.writeString(corrupt, "corrupt");
        var outcome = maintenance.cleanupExpired(false);
        assertNotNull(outcome.getFirst().errorCode());
        assertNotNull(jdbc.queryForObject("SELECT cleanup_started_at FROM heron_video_version WHERE version_id=?", Object.class, f.versionId()));
        assertNull(jdbc.queryForObject("SELECT cleaned_at FROM heron_video_version WHERE version_id=?", Object.class, f.versionId()));
        assertEquals(410, assertThrows(HeronBusinessException.class,
            () -> news.preview(f.workId(), f.versionId(), f.file("mp4").artifactId())).getStatus());
        Files.write(corrupt, original);
        maintenance.cleanupExpired(false);
        assertNotNull(news.version(f.workId(), f.versionId()).getCleanedAt());
    }

    @Test
    void failureEvidenceKeepAlsoProtectsCompletedVersion() throws Exception {
        var f = completed();
        expire(f.versionId());
        Long taskId = jdbc.queryForObject("SELECT task_id FROM heron_video_version WHERE version_id=?", Long.class, f.versionId());
        var keep = properties.getDataRoot().resolve("work/news_video/" + taskId + "/a1-fixture/.keep");
        Files.createDirectories(keep);
        assertTrue(maintenance.cleanupExpired(false).isEmpty());
        assertTrue(Files.exists(properties.getDataRoot().resolve(f.file("mp4").storageKey())));
    }

    private void expire(long versionId) {
        jdbc.update("UPDATE heron_video_version SET create_time=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 31 DAY) WHERE version_id=?", versionId);
    }
}
