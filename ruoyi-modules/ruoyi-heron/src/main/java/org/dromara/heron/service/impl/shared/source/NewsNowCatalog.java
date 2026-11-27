package org.dromara.heron.service.impl.shared.source;

import org.dromara.heron.domain.HeronBusinessException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * NewsNow 平台目录：只登记平台 ID 与展示名，接口地址由 heron.news.newsnow-base-url 拼出。
 * 目录里的平台均已在公共实例实测可用；新增平台只需在此登记，不改表结构。
 */
public final class NewsNowCatalog {

    /**
     * 一个 NewsNow 平台条目。
     *
     * @param id   平台标识，直接作为接口的 id 参数
     * @param name 展示名
     */
    public record Platform(String id, String name) {
    }

    public static final List<Platform> ALL = List.of(
        new Platform("weibo", "微博"),
        new Platform("zhihu", "知乎"),
        new Platform("douyin", "抖音"),
        new Platform("bilibili-hot-search", "B站热搜"),
        new Platform("toutiao", "今日头条"),
        new Platform("baidu", "百度热搜"),
        new Platform("thepaper", "澎湃新闻"),
        new Platform("cls-hot", "财联社热门"),
        new Platform("cls-telegraph", "财联社电报"),
        new Platform("wallstreetcn-hot", "华尔街见闻"),
        new Platform("wallstreetcn-news", "华尔街见闻资讯"),
        new Platform("tieba", "贴吧"),
        new Platform("ifeng", "凤凰网"),
        new Platform("aihot", "AI 热点"),
        new Platform("cankaoxiaoxi", "参考消息"),
        new Platform("chongbuluo", "虫部落"),
        new Platform("coolapk", "酷安"),
        new Platform("dongqiudi", "懂球帝"),
        new Platform("douban", "豆瓣"),
        new Platform("fastbull", "法布财经"),
        new Platform("freebuf", "FreeBuf"),
        new Platform("gelonghui", "格隆汇"),
        new Platform("github", "GitHub Trending"),
        new Platform("hackernews", "Hacker News"),
        new Platform("hupu", "虎扑"),
        new Platform("iqiyi", "爱奇艺"),
        new Platform("ithome", "IT之家"),
        new Platform("jin10", "金十数据"),
        new Platform("juejin", "掘金"),
        new Platform("kaopu", "靠谱新闻"),
        new Platform("mktnews", "市场资讯"),
        new Platform("nowcoder", "牛客"),
        new Platform("pcbeta", "远景论坛"),
        new Platform("producthunt", "Product Hunt"),
        new Platform("qqvideo", "腾讯视频"),
        new Platform("solidot", "Solidot"),
        new Platform("sputniknewscn", "卫星通讯社"),
        new Platform("sspai", "少数派"),
        new Platform("steam", "Steam"),
        new Platform("tencent-hot", "腾讯新闻热点"),
        new Platform("v2ex", "V2EX"),
        new Platform("xueqiu", "雪球"),
        new Platform("zaobao", "联合早报")
    );

    private NewsNowCatalog() {
    }

    /** 按平台标识查目录，未登记时返回 empty。 */
    public static Optional<Platform> find(String id) {
        return ALL.stream().filter(platform -> platform.id().equals(id)).findFirst();
    }

    /** 从来源地址的 id 参数取平台标识；没有或非法时返回 null。 */
    public static String platformId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String query = URI.create(url.strip()).getRawQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int at = pair.indexOf('=');
                if (at > 0 && "id".equals(pair.substring(0, at))) {
                    String value = pair.substring(at + 1);
                    return value.isBlank() ? null : value;
                }
            }
        } catch (RuntimeException ignored) {
            // 非法地址由保存时的结构校验报错，这里只负责识别平台。
        }
        return null;
    }

    /**
     * 拼平台抓取地址：{@code <baseUrl>/api/s?id=<platformId>}。
     *
     * @throws HeronBusinessException 503 {@code news_source_platform} 未配置 NewsNow 基址
     */
    public static String feedUrl(String baseUrl, String platformId) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new HeronBusinessException(503, "news_source_platform", "未配置 NewsNow 基址");
        }
        return base + "/api/s?id=" + URLEncoder.encode(platformId, StandardCharsets.UTF_8);
    }
}
