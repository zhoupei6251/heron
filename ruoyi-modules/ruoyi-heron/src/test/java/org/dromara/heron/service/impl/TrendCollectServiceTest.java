package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronTrendVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时长过滤只做一件事：把长片挡在候选之外。
 *
 * <p>时长未知时不拦——抓不到时长不等于这条片不能拆，交给下游自己判。
 */
@DisplayName("热点候选：只留够短的")
class TrendCollectServiceTest {

    private static TrendCollectService service(long maxDurationMs) {
        HeronNewsProperties news = new HeronNewsProperties();
        news.setTrendMaxDurationMs(maxDurationMs);
        return new TrendCollectService(null, news);
    }

    private static HeronTrendVideo video(Long durationMs) {
        HeronTrendVideo entity = new HeronTrendVideo();
        entity.setDurationMs(durationMs);
        return entity;
    }

    @Test
    void keepsShortVideos() {
        assertTrue(service(90_000).shortEnough(video(27_034L)));
        assertTrue(service(90_000).shortEnough(video(90_000L)), "正好等于上限仍算够短");
    }

    @Test
    void rejectsLongVideos() {
        assertFalse(service(90_000).shortEnough(video(226_719L)), "热榜里混着几分钟的长片");
        assertFalse(service(90_000).shortEnough(video(306_363L)));
    }

    @Test
    void unknownDurationIsNotRejected() {
        assertTrue(service(90_000).shortEnough(video(null)), "抓不到时长不等于不能拆");
    }

    @Test
    void zeroLimitDisablesTheFilter() {
        assertTrue(service(0).shortEnough(video(600_000L)), "上限配 0 表示不过滤");
    }
}
