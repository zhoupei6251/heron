package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.NewsPublishUrlBo;
import org.dromara.heron.domain.bo.NewsPublishBo;
import org.dromara.heron.domain.bo.NewsPublishResolveBo;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.support.DouyinCliPublishTestConfiguration;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.dromara.heron.support.NewsMysqlTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/** 命令行退出0只记录已提交；平台链接和实际发布时间经人工核对后才成为发布事实。 */
@ContextConfiguration(classes = {NewsMysqlTestConfiguration.class, DouyinCliPublishTestConfiguration.class},
    inheritLocations = false)
class NewsDouyinCliPublishTest extends NewsMysqlTestSupport {

    @Test
    @DisplayName("确认后只落提交尝试，不伪造已发布事实")
    void confirmLeavesSubmittedWithoutLink() throws Exception {
        var version = completed();

        NewsVideoVersionVo vo = news.confirm(version.workId(), version.versionId(), version.approve());

        assertNull(vo.getPublishSource());
        assertNull(vo.getPublishUrl());
        assertNull(vo.getPublishAt());
        assertEquals("submitted", vo.getPublishAttempt().getStatus());
        assertEquals("heron", vo.getPublishAttempt().getAccount());
        assertEquals(vo.getPublishAttempt().getAttemptId(),
            news.confirm(version.workId(), version.versionId(), version.approve()).getPublishAttempt().getAttemptId());
    }

    @Test
    @DisplayName("已提交尝试核对真实平台回执后才写发布事实，并支持幂等重放")
    void resolutionBindsActualTimeAndFingerprint() throws Exception {
        var version = completed();
        NewsVideoVersionVo submitted = news.confirm(version.workId(), version.versionId(), version.approve());

        var receipt = new NewsPublishBo("https://www.douyin.com/video/7400000000000000000", "heron",
            java.time.LocalDateTime.now(clock).minusMinutes(1), version.file("mp4").sha256());
        var resolution = new NewsPublishResolveBo("published", receipt.videoSha256(), "已在平台核对视频和实际发布时间", false, receipt);
        var wrongAccount = new NewsPublishBo(receipt.workUrl(), "other-account", receipt.publishedAt(), receipt.videoSha256());
        assertThrows(HeronBusinessException.class, () -> news.resolvePublishAttempt(version.workId(), version.versionId(),
            submitted.getPublishAttempt().getAttemptId(), new NewsPublishResolveBo("published", receipt.videoSha256(), "核对", false, wrongAccount)));
        NewsVideoVersionVo filled = news.resolvePublishAttempt(version.workId(), version.versionId(),
            submitted.getPublishAttempt().getAttemptId(), resolution);

        assertEquals("https://www.douyin.com/video/7400000000000000000", filled.getPublishUrl());
        assertEquals(receipt.publishedAt(), filled.getPublishAt());
        assertEquals(receipt.videoSha256(), filled.getPublishVideoSha());
        assertEquals("published", filled.getPublishAttempt().getStatus());
        assertEquals(filled.getPublishUrl(), news.resolvePublishAttempt(version.workId(), version.versionId(),
            submitted.getPublishAttempt().getAttemptId(), resolution).getPublishUrl());
    }

    @Test
    void onlyExplicitStoppedAndNotPublishedResolutionReleasesTheCandidate() throws Exception {
        var version = completed();
        var submitted = news.confirm(version.workId(), version.versionId(), version.approve());
        long attempt = submitted.getPublishAttempt().getAttemptId();
        String sha = version.file("mp4").sha256();
        assertThrows(HeronBusinessException.class, () -> news.resolvePublishAttempt(version.workId(), version.versionId(),
            attempt, new NewsPublishResolveBo("not_published", sha, "已检查平台", false, null)));
        var resolved = news.resolvePublishAttempt(version.workId(), version.versionId(), attempt,
            new NewsPublishResolveBo("not_published", sha, "上传已停止，平台确认没有这条作品", true, null));
        assertEquals("not_published", resolved.getPublishAttempt().getStatus());
        assertNull(resolved.getPublishAt());
        var retried = news.confirm(version.workId(), version.versionId(), version.approve());
        assertNotEquals(attempt, retried.getPublishAttempt().getAttemptId());
    }

    @Test
    @DisplayName("非抖音链接不写进回执")
    void backfillRejectsForeignUrl() throws Exception {
        var version = completed();
        news.confirm(version.workId(), version.versionId(), version.approve());

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> news.backfillPublishUrl(version.workId(), version.versionId(),
                new NewsPublishUrlBo("https://example.test/video/1")));

        assertEquals("news_invalid_input", error.getCode());
    }
}
