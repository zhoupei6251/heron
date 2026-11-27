package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronTrendHotword;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.service.impl.shared.research.TrendCollectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return new TrendCollectService(null, null, news, null);
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

    @Test
    @DisplayName("热词重采：次数累加，首次见到的时间不动")
    void mergeHotwordAccumulates() {
        LocalDateTime first = LocalDateTime.of(2026, 11, 20, 7, 0);
        HeronTrendHotword existing = hotword(first, 3, 5L);
        HeronTrendHotword incoming = hotword(LocalDateTime.of(2026, 11, 24, 7, 0), 8, 1L);

        TrendCollectService.mergeHotword(incoming, existing);

        assertEquals(6L, incoming.getHitCount(), "第 5 次采到后再来一次是第 6 次");
        assertEquals(first, incoming.getFirstSeenAt(), "首次见到的时间不该被这一轮覆盖");
    }

    @Test
    @DisplayName("热词重采：历史最好位次只降不升")
    void mergeHotwordKeepsBestRank() {
        HeronTrendHotword existing = hotword(LocalDateTime.now(), 3, 1L);

        HeronTrendHotword dropped = hotword(LocalDateTime.now(), 8, 1L);
        TrendCollectService.mergeHotword(dropped, existing);
        assertEquals(3, dropped.getBestRank(), "这轮掉到第 8 名，不能抹掉到过第 3 名");

        HeronTrendHotword improved = hotword(LocalDateTime.now(), 1, 1L);
        TrendCollectService.mergeHotword(improved, existing);
        assertEquals(1, improved.getBestRank(), "冲到第 1 名要记下来");
    }

    @Test
    @DisplayName("热词重采：置顶条没有位次，不能把已有最好位次清空")
    void mergeHotwordKeepsBestRankWhenIncomingHasNone() {
        HeronTrendHotword existing = hotword(LocalDateTime.now(), 2, 1L);
        HeronTrendHotword incoming = hotword(LocalDateTime.now(), null, 1L);

        TrendCollectService.mergeHotword(incoming, existing);

        assertEquals(2, incoming.getBestRank(), "位次为空不等于「比第 2 名更好」");
    }

    private static HeronTrendHotword hotword(LocalDateTime seenAt, Integer bestRank, Long hitCount) {
        HeronTrendHotword word = new HeronTrendHotword();
        word.setFirstSeenAt(seenAt);
        word.setBestRank(bestRank);
        word.setHitCount(hitCount);
        return word;
    }
}
