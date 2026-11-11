package org.dromara.heron.service;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.NewsPublishUrlBo;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.support.DouyinCliPublishTestConfiguration;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.dromara.heron.support.NewsMysqlTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/** 命令行发布只留「已提交」事实；链接事后补一次，补不到也不回滚已发布的事实。 */
@ContextConfiguration(classes = {NewsMysqlTestConfiguration.class, DouyinCliPublishTestConfiguration.class},
    inheritLocations = false)
class NewsDouyinCliPublishTest extends NewsMysqlTestSupport {

    @Test
    @DisplayName("确认后落命令行回执：有时间、无链接")
    void confirmLeavesSubmittedWithoutLink() throws Exception {
        var version = completed();

        NewsVideoVersionVo vo = news.confirm(version.workId(), version.versionId(), version.approve());

        assertEquals("douyin-cli", vo.getPublishSource());
        assertNull(vo.getPublishUrl());
        assertNotNull(vo.getPublishAt());
        assertEquals("heron", vo.getPublishAccount());
    }

    @Test
    @DisplayName("补链接只改链接，发布时间与成片指纹不动")
    void backfillKeepsPublishTimeAndFingerprint() throws Exception {
        var version = completed();
        NewsVideoVersionVo submitted = news.confirm(version.workId(), version.versionId(), version.approve());

        NewsVideoVersionVo filled = news.backfillPublishUrl(version.workId(), version.versionId(),
            new NewsPublishUrlBo("https://www.douyin.com/video/7400000000000000000"));

        assertEquals("https://www.douyin.com/video/7400000000000000000", filled.getPublishUrl());
        assertEquals(submitted.getPublishAt(), filled.getPublishAt());
        assertEquals(submitted.getPublishVideoSha(), filled.getPublishVideoSha());
        assertEquals(filled.getPublishUrl(), news.backfillPublishUrl(version.workId(), version.versionId(),
            new NewsPublishUrlBo("https://www.douyin.com/video/7400000000000000000")).getPublishUrl());
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
