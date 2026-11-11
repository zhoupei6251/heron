package org.dromara.heron.service;

import org.dromara.heron.domain.bo.NewsReviewBo;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 一次确认：通过即写入沙箱回执，重复确认幂等；驳回只落审核结论。 */
class NewsConfirmTest extends NewsMysqlTestSupport {

    @Test
    void approvalWritesSandboxReceiptAndReplays() throws Exception {
        var version = completed();

        NewsVideoVersionVo vo = news.confirm(version.workId(), version.versionId(), version.approve());
        assertEquals("approved", vo.getReviewStatus());
        assertEquals("sandbox", vo.getPublishSource());
        assertTrue(vo.getPublishUrl().startsWith("sandbox://news/"), vo.getPublishUrl());
        assertEquals(version.file("mp4").sha256(), vo.getPublishVideoSha());
        assertNotNull(vo.getPublishAt());
        assertEquals(1, publishRowCount(version.workId()));

        NewsVideoVersionVo again = news.confirm(version.workId(), version.versionId(), version.approve());
        assertEquals(vo.getPublishUrl(), again.getPublishUrl());
        assertEquals(vo.getPublishAt(), again.getPublishAt());
        assertEquals(1, publishRowCount(version.workId()));
    }

    @Test
    void rejectionRecordsDecisionWithoutPublishing() throws Exception {
        var version = completed();

        NewsVideoVersionVo vo = news.confirm(version.workId(), version.versionId(),
            new NewsReviewBo("rejected", version.file("contact_sheet").sha256(), version.file("mp4").sha256(), "画面不合规"));
        assertEquals("rejected", vo.getReviewStatus());
        assertNull(vo.getPublishSource());
        assertNull(vo.getPublishAt());
        assertEquals(0, publishRowCount(version.workId()));
    }

    private int publishRowCount(long workId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM heron_video_version WHERE work_id = ? AND publish_at IS NOT NULL",
            Integer.class, workId);
    }
}
