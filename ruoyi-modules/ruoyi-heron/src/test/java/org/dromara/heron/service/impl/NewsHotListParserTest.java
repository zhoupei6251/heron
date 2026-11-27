package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.research.NewsSourceFetcher;
import org.dromara.heron.service.impl.shared.source.NewsFeedItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 热榜解析：只验形状与条目提取，不碰网络。 */
@DisplayName("新闻采集：头条/B站/澎湃/知乎直连与 NewsNow 解析")
class NewsHotListParserTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("缺少数据或 JSON 损坏直接失败")
    void rejectsBrokenBody() {
        assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseToutiao(utf8("not json")));
    }

    @Test
    @DisplayName("NewsNow：名次优先取 band_rank，缺省按数组下标，链接剥掉排名参数")
    void newsnow() {
        String body = "{\"status\":\"cache\",\"id\":\"weibo\",\"items\":["
            + "{\"id\":\"a\",\"title\":\"话题一\","
            + "\"url\":\"https://s.weibo.com/weibo?q=%23%E8%AF%9D%E9%A2%98%E4%B8%80%23&t=31&band_rank=7&Refer=top\"},"
            + "{\"id\":\"b\",\"title\":\"话题二\",\"url\":\"https://s.weibo.com/weibo?q=%23%E8%AF%9D%E9%A2%98%E4%BA%8C%23\"}]}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseNewsNow(utf8(body), "weibo", true);
        assertEquals(2, items.size());
        assertEquals(7, items.get(0).rank());
        assertEquals(2, items.get(1).rank());
        assertFalse(items.get(0).url().contains("band_rank"));
        assertFalse(items.get(0).url().contains("Refer"));
        assertFalse(items.get(0).url().contains("&t=31"));
        assertTrue(items.get(0).topicOnly());
    }

    @Test
    @DisplayName("NewsNow：extra.info 作为摘要，允许正文时 topicOnly 为 false")
    void newsnowSummaryAndBodyFlag() {
        String body = "{\"items\":[{\"id\":\"q\",\"title\":\"知乎问题\","
            + "\"url\":\"https://www.zhihu.com/question/1\",\"extra\":{\"info\":\"热度 123 万\"}}]}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseNewsNow(utf8(body), "zhihu", false);
        assertEquals("热度 123 万", items.get(0).summary());
        assertFalse(items.get(0).topicOnly());
    }

    @Test
    @DisplayName("NewsNow：缺少 items 归因为平台错误，不伪装成空结果")
    void newsnowRejectsMissingItems() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseNewsNow(utf8("{\"status\":\"success\"}"), "not-exist", true));
        assertEquals("news_source_platform", error.getCode());
    }

    @Test
    @DisplayName("头条：名次按数组顺序，链接剥掉统计参数，摘要取 HotValue")
    void toutiao() {
        String body = "{\"data\":[{\"Title\":\"头条一\","
            + "\"Url\":\"https://www.toutiao.com/trending/700/?log_pb=x&rank=99\","
            + "\"HotValue\":\"1200\",\"ClusterIdStr\":\"700\"}]}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseToutiao(utf8(body));
        assertEquals(1, items.size());
        assertEquals("头条一", items.get(0).title());
        assertEquals("https://www.toutiao.com/trending/700/", items.get(0).url());
        assertEquals(1, items.get(0).rank());
        assertEquals("热度 1200", items.get(0).summary());
        assertTrue(items.get(0).topicOnly());
    }

    @Test
    @DisplayName("B站：按 show_name 建站内搜索链接，名次按数组顺序")
    void bilibili() {
        String body = "{\"code\":0,\"data\":{\"trending\":{\"list\":["
            + "{\"keyword\":\"话题\",\"show_name\":\"话题 A\",\"heat_score\":99}]}}}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseBilibili(utf8(body));
        assertEquals(1, items.size());
        assertEquals("话题 A", items.get(0).title());
        assertEquals(1, items.get(0).rank());
        assertTrue(items.get(0).url().startsWith("https://search.bilibili.com/all?keyword="));
        assertTrue(items.get(0).topicOnly());
    }

    @Test
    @DisplayName("B站：风控码归因为 blocked，其余错误码归因为平台错误")
    void bilibiliErrorCodes() {
        HeronBusinessException blocked = assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseBilibili(utf8("{\"code\":-412,\"message\":\"请求被拦截\"}")));
        assertEquals("news_source_blocked", blocked.getCode());
        HeronBusinessException platform = assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseBilibili(utf8("{\"code\":-1,\"message\":\"服务异常\"}")));
        assertEquals("news_source_platform", platform.getCode());
    }

    @Test
    @DisplayName("澎湃：link 为空或指向站外时，按 contId 拼站内详情地址")
    void thepaper() {
        String body = "{\"resultCode\":1,\"data\":{\"hotNews\":["
            + "{\"name\":\"澎湃一\",\"contId\":\"123\",\"link\":\"\",\"nodeInfo\":{\"name\":\"要闻\"}},"
            + "{\"name\":\"澎湃二\",\"contId\":\"456\","
            + "\"link\":\"https://m.thepaper.cn/newsDetail_forward_456\"}]}}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseThepaper(utf8(body));
        assertEquals(2, items.size());
        assertEquals("https://www.thepaper.cn/newsDetail_forward_123", items.get(0).url());
        assertEquals("要闻", items.get(0).summary());
        assertEquals("https://www.thepaper.cn/newsDetail_forward_456", items.get(1).url());
        assertFalse(items.get(0).topicOnly());
    }

    @Test
    @DisplayName("知乎：接口域链接换成问题页，摘要取 excerpt")
    void zhihu() {
        String body = "{\"data\":[{\"target\":{\"id\":\"42\",\"title\":\"知乎问题\","
            + "\"url\":\"https://api.zhihu.com/questions/42\",\"excerpt\":\"摘要\"}}]}";
        List<NewsFeedItem> items = NewsSourceFetcher.parseZhihu(utf8(body));
        assertEquals(1, items.size());
        assertEquals("https://www.zhihu.com/question/42", items.get(0).url());
        assertEquals("摘要", items.get(0).summary());
        assertEquals(1, items.get(0).rank());
        assertTrue(items.get(0).topicOnly());
    }

    @Test
    @DisplayName("直连热榜返回 HTML 时按风控归因，不落到解析失败")
    void hotListHtmlIsBlocked() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> NewsSourceFetcher.parseZhihu(utf8("<html><body>验证码</body></html>")));
        assertEquals("news_source_blocked", error.getCode());
    }
}
