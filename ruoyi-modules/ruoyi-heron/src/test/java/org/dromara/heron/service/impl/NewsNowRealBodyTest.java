package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NewsNow 真实响应回归：样本取自 2026-11-24 公共实例的实际返回。
 *
 * <p>最关键的一条：{@code status: "cache"} 不是失败。NewsNow 内部有 30 分钟缓存，
 * 缓存命中的响应同样是完整数据，只是 status 字段换了个值。把它当错误处理，
 * 等于一多半时间拿不到热榜。
 */
@DisplayName("NewsNow 热榜：真实响应形状")
class NewsNowRealBodyTest {

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = NewsNowRealBodyTest.class.getResourceAsStream("/news-feeds/newsnow-" + name + ".json")) {
            assertNotNull(in, "缺少样本 " + name);
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("status=cache 也能解析出条目：缓存命中不是失败")
    void cachedStatusStillParses() throws IOException {
        List<NewsFeedItem> items = NewsSourceFetcher.parseNewsNow(sample("douyin"), "douyin", true);
        assertEquals(3, items.size());
        assertFalse(items.get(0).title().isBlank());
        assertTrue(items.get(0).url().startsWith("https://"));
        assertTrue(items.get(0).topicOnly(), "热榜条目默认只当话题，不抓正文");
    }

    @Test
    @DisplayName("微博：链接剥掉 band_rank，落库不会被排名变化拆成多条")
    void weiboStripsRankParam() throws IOException {
        List<NewsFeedItem> items = NewsSourceFetcher.parseNewsNow(sample("weibo"), "weibo", true);
        assertEquals(3, items.size());
        for (NewsFeedItem item : items) {
            assertFalse(item.url().contains("band_rank"), "排名参数必须剥掉：" + item.url());
        }
        assertEquals(1, items.get(0).rank(), "名次仍从 band_rank 读出");
    }

    @Test
    @DisplayName("知乎：问句标题原样保留，名次连续")
    void zhihuKeepsOrder() throws IOException {
        List<NewsFeedItem> items = NewsSourceFetcher.parseNewsNow(sample("zhihu"), "zhihu", true);
        assertEquals(3, items.size());
        assertEquals(1, items.get(0).rank());
        assertEquals(2, items.get(1).rank());
    }

    @Test
    @DisplayName("缺 items 仍归因为平台错误，不伪装成空结果")
    void missingItemsIsPlatformError() {
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseNewsNow("{\"status\":\"cache\"}".getBytes(StandardCharsets.UTF_8),
                "weibo", true));
        assertEquals("news_source_platform", e.getCode());
    }
}
