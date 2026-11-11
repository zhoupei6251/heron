package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 热榜解析：只验形状与条目提取，不碰网络。 */
@DisplayName("新闻采集：微博/百度热榜解析")
class NewsHotListParserTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("微博：取 realtime，条目链接指向站内搜索")
    void weibo() {
        String body = "{\"ok\":1,\"data\":{\"realtime\":["
            + "{\"word\":\"话题一\",\"word_scheme\":\"#话题一#\"},"
            + "{\"word\":\"话题二\"}]}}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseWeibo(utf8(body));
        assertEquals(2, items.size());
        assertEquals("话题一", items.get(0).title());
        assertEquals(1, items.get(0).rank());
        assertTrue(items.get(0).url().startsWith("https://s.weibo.com/weibo?q="));
    }

    @Test
    @DisplayName("百度：从 s-data 的 hotList 卡片取 word/url")
    void baidu() {
        String body = "<html><body><!--s-data:{\"data\":{\"cards\":["
            + "{\"component\":\"hotList\",\"content\":["
            + "{\"word\":\"新闻一\",\"url\":\"https://www.baidu.com/s?wd=1\"},"
            + "{\"word\":\"新闻二\",\"url\":\"https://www.baidu.com/s?wd=2\"}]}]}}--></body></html>";
        List<NewsFeedItem> items = NewsSourceFetcher.parseBaidu(utf8(body));
        assertEquals(2, items.size());
        assertEquals("新闻一", items.get(0).title());
        assertEquals("https://www.baidu.com/s?wd=1", items.get(0).url());
    }

    @Test
    @DisplayName("缺少数据或 JSON 损坏直接失败")
    void rejectsBrokenBody() {
        assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseWeibo(utf8("not json")));
        assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseBaidu(utf8("<html></html>")));
    }
}
