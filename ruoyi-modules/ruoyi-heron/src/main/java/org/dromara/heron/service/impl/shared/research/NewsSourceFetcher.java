package org.dromara.heron.service.impl.shared.research;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.service.impl.lane.news.NewsArticleBody;
import org.dromara.heron.service.impl.shared.source.NewsAddressPolicy;
import org.dromara.heron.service.impl.shared.source.NewsFeedItem;
import org.dromara.heron.service.impl.shared.source.NewsNowCatalog;
import org.dromara.heron.service.impl.shared.source.NewsTransport;
import org.dromara.heron.service.impl.shared.source.NewsUrlPolicy;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 来源抓取：白名单→解析→地址策略→单次出网逐跳执行，禁自动重定向、自动重试与编码猜测。
 */
@Service
@RequiredArgsConstructor
public class NewsSourceFetcher {

    static final int MAX_REDIRECTS = 3;
    public static final int MAX_RAW_BYTES = 1 << 20;
    static final int MAX_TEXT_BYTES = 2 << 20;
    static final int MAX_ITEMS = 200;
    static final int ARTICLE_CODE_POINTS = 12_000;
    /** 单张图上限：正文配图不该有几 MB，超了通常是原图直出，压不进短视频。 */
    static final int MAX_IMAGE_BYTES = 8 << 20;
    private static final int TITLE_CODE_POINTS = 80;
    private static final Pattern BAND_RANK = Pattern.compile("[?&]band_rank=(\\d+)");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NewsTransport transport;
    private final NewsTransport.Resolver resolver;
    private final HeronNewsProperties properties;

    /**
     * 抓取并解析一条来源：白名单校验 → 出网（禁自动重定向/重试）→ 按 sourceType 选解析器。
     * NewsNow 平台不可用单独归因为 502 {@code news_source_platform}，不退化成「上游没数据」。
     *
     * @return 解析出的条目；具体条数上限由各解析器保证
     * @throws HeronBusinessException 400 {@code news_source_type} 未知来源类型，或地址策略拒绝
     */
    public List<NewsFeedItem> fetch(HeronNewsSource source) {
        NewsTransport.Response response;
        try {
            response = request(NewsUrlPolicy.normalize(source.getUrl()));
        } catch (HeronBusinessException e) {
            // NewsNow 对非法平台返回 500，必须单独归因，不能退化成「上游没数据」。
            if ("newsnow".equals(source.getSourceType()) && "news_source_status".equals(e.getCode())) {
                throw new HeronBusinessException(502, "news_source_platform",
                    "NewsNow 平台不可用(" + NewsNowCatalog.platformId(source.getUrl()) + "): " + e.getMessage());
            }
            throw e;
        }
        byte[] body = decode(response);
        return switch (source.getSourceType()) {
            case "rss", "atom" -> parseXml(body);
            case "text" -> parseText(body);
            case "toutiao" -> parseToutiao(body);
            case "bilibili" -> parseBilibili(body);
            case "thepaper" -> parseThepaper(body);
            case "zhihu" -> parseZhihu(body);
            case "newsnow" -> parseNewsNow(body, NewsNowCatalog.platformId(source.getUrl()),
                !properties.isNewsnowArticleBodyAllowed());
            default -> throw new HeronBusinessException(400, "news_source_type", "未知的来源类型: " + source.getSourceType());
        };
    }

    /** 正文抓取沿用采集的白名单、逐跳解析与地址闸：失败就是失败，不换源、不退回标题、不猜正文。 */
    public NewsArticleBody fetchArticle(URI url) {
        URI target = NewsUrlPolicy.normalize(url.toString());
        byte[] body = decode(request(target));
        String html = new String(body, StandardCharsets.UTF_8);
        int cut = html.length() <= ARTICLE_CODE_POINTS ? html.length() : html.offsetByCodePoints(0, ARTICLE_CODE_POINTS);
        // shortcut: 哈希取整篇、喂模型的是截断前缀；正文噪声大到影响出稿时再引正文抽取库
        return new NewsArticleBody(NewsUrlPolicy.canonical(target.toString()), LocalDateTime.now(ZoneOffset.UTC),
            html.substring(0, cut), DigestUtil.sha256Hex(body));
    }

    /**
     * 下载正文里的一张图。走和正文同一套出网闸（白名单 + 私网拦截 + 重定向次数），
     * 不因为是图片就放宽——图片地址同样可能被指向内网。
     *
     * @return 图片字节；类型不是图片、超过上限或站点不可达时返回 empty，由调用方退化成无图场景
     */
    public Optional<byte[]> fetchImage(URI url) {
        URI target;
        try {
            target = NewsUrlPolicy.normalize(url.toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        NewsTransport.Response response;
        try {
            response = request(target);
        } catch (RuntimeException e) {
            // 单张图取不到不该让整条片子失败：退化成纯文字场景，比整片报废合理
            return Optional.empty();
        }
        String type = response.contentType() == null ? "" : response.contentType().toLowerCase();
        if (!type.startsWith("image/")) {
            return Optional.empty();
        }
        byte[] body;
        try {
            body = decode(response);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (body.length == 0 || body.length > MAX_IMAGE_BYTES) {
            return Optional.empty();
        }
        return Optional.of(body);
    }

    private NewsTransport.Response request(URI start) {
        URI current = start;
        String previousScheme = null;
        for (int followed = 0; ; followed++) {
            InetAddress pinned = gate(current);
            // 降级检查放在白名单与地址闸之后：转向私网时要报 blocked，而不是被降级理由抢先。
            if ("https".equals(previousScheme) && !"https".equals(current.getScheme())) {
                throw new HeronBusinessException(400, "news_source_scheme", "来源重定向不允许 HTTPS 降级");
            }
            previousScheme = current.getScheme();
            NewsTransport.Response response;
            try {
                response = transport.get(new NewsTransport.Request(current, pinned));
            } catch (IOException e) {
                throw new HeronBusinessException(502, "news_source_unreachable", "来源无法访问: " + current.getHost());
            }
            int status = response.status();
            if (status >= 300 && status < 400) {
                if (followed >= MAX_REDIRECTS) {
                    throw new HeronBusinessException(502, "news_source_redirect_limit", "来源重定向次数超过上限");
                }
                if (response.location() == null || response.location().isBlank()) {
                    throw new HeronBusinessException(502, "news_source_status", "来源重定向缺少目标地址");
                }
                current = NewsUrlPolicy.normalize(current.resolve(response.location()).toString());
                continue;
            }
            // 401/403 是登录或风控拒绝，429 是限流；单独归因，别混进「上游没数据」。
            if (status == 401 || status == 403) {
                throw new HeronBusinessException(502, "news_source_auth", "来源要求登录或拒绝访问: " + status);
            }
            if (status == 429) {
                throw new HeronBusinessException(502, "news_source_rate_limited", "来源限流: " + status);
            }
            if (status != 200) {
                throw new HeronBusinessException(502, "news_source_status", "来源返回异常状态: " + status);
            }
            return response;
        }
    }

    /**
     * 出网前的两道闸，顺序不可调换：域名先比白名单再解析（空清单不得发起解析）；
     * 字面 IP 先过地址策略，私网/保留段要报 blocked，只有公网 IP 才轮到白名单报 host。
     */
    private InetAddress gate(URI uri) {
        String host = NewsUrlPolicy.hostOf(uri);
        boolean literal = NewsUrlPolicy.isIpLiteral(host);
        List<InetAddress> resolved;
        if (literal) {
            resolved = publicAddress(host);
        } else {
            NewsUrlPolicy.requireAllowedHost(properties, uri);
            resolved = publicAddress(host);
        }
        if (literal) NewsUrlPolicy.requireAllowedHost(properties, uri);
        return resolved.get(0);
    }

    /** 每一跳都重新解析并逐个地址校验，DNS 重绑定无法绕过。 */
    private List<InetAddress> publicAddress(String host) {
        List<InetAddress> resolved;
        try {
            resolved = resolver.resolve(host);
        } catch (IOException e) {
            throw new HeronBusinessException(502, "news_source_unreachable", "来源域名无法解析: " + host);
        }
        return NewsAddressPolicy.requirePublic(resolved, host);
    }

    private byte[] decode(NewsTransport.Response response) {
        byte[] raw = response.body() == null ? new byte[0] : response.body();
        if (raw.length > MAX_RAW_BYTES) throw tooLarge("来源响应体超过 1 MiB 上限");
        String encoding = response.contentEncoding();
        if (encoding == null || encoding.isBlank() || encoding.equalsIgnoreCase("identity")) return raw;
        if (!encoding.equalsIgnoreCase("gzip")) {
            throw new HeronBusinessException(502, "news_source_encoding", "来源使用了未支持的压缩编码: " + encoding);
        }
        ByteArrayOutputStream inflated = new ByteArrayOutputStream();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                if (inflated.size() + read > MAX_TEXT_BYTES) throw tooLarge("来源解压后超过 2 MiB 上限");
                inflated.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new HeronBusinessException(502, "news_source_encoding", "来源压缩内容无法解压");
        }
        return inflated.toByteArray();
    }

    private List<NewsFeedItem> parseXml(byte[] body) {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new HeronBusinessException(503, "news_source_xml", "来源解析器不可用");
        }
        List<NewsFeedItem> items = new ArrayList<>();
        try {
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            for (String tag : List.of("item", "entry")) {
                NodeList nodes = document.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength() && items.size() < MAX_ITEMS; i++) {
                    Element node = (Element) nodes.item(i);
                    String title = child(node, "title");
                    if (title.isBlank()) continue;
                    String summary = firstNonBlank(child(node, "description"), child(node, "summary"),
                        child(node, "content"), child(node, "content:encoded"), title);
                    String link = firstNonBlank(child(node, "link"), attribute(node, "link", "href"));
                    String itemId = firstNonBlank(child(node, "guid"), child(node, "id"),
                        NewsUrlPolicy.canonical(link));
                    items.add(new NewsFeedItem(title, summary, NewsUrlPolicy.canonical(link), itemId,
                        items.size() + 1));
                }
            }
        } catch (SAXException | IOException | IllegalArgumentException | ParserConfigurationException e) {
            throw new HeronBusinessException(502, "news_source_xml", "来源内容不是可解析的 XML");
        }
        return items;
    }

    /** 纯文本按行入库：不解析标记，不编造链接。 */
    private List<NewsFeedItem> parseText(byte[] body) {
        List<NewsFeedItem> items = new ArrayList<>();
        for (String line : new String(body, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String value = line.strip();
            if (value.isEmpty() || items.size() >= MAX_ITEMS) continue;
            items.add(new NewsFeedItem(truncate(value), value, null, null, items.size() + 1));
        }
        return items;
    }

    /** 直连热榜统一入口：正常返回 JSON；返回 HTML 通常是风控/验证页，单独归因。 */
    private static JsonNode json(byte[] body, String label) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        if (text.startsWith("<")) {
            throw new HeronBusinessException(502, "news_source_blocked", label + "返回 HTML，疑似风控或验证页");
        }
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(502, "news_source_body", label + "返回不是 JSON");
        }
    }

    /**
     * 头条热榜：data[] 的 Title/Url/HotValue；名次按数组顺序，链接只保留身份路径。
     * 详情页是 JS 壳，抓不到正文，只作选题线索；正文后续由大模型检索补。
     */
    public static List<NewsFeedItem> parseToutiao(byte[] body) {
        JsonNode items = json(body, "头条热榜").path("data");
        if (!items.isArray()) {
            throw new HeronBusinessException(502, "news_source_body", "头条热榜缺少 data 数组");
        }
        List<NewsFeedItem> list = new ArrayList<>();
        for (JsonNode item : items) {
            if (list.size() >= MAX_ITEMS) break;
            String title = item.path("Title").asString("").strip();
            String url = identityUrl(item.path("Url").asString(""));
            if (title.isEmpty() || url.isEmpty()) continue;
            String heat = numberText(item.path("HotValue"));
            list.add(new NewsFeedItem(truncate(title), heat.isEmpty() ? "头条热榜" : "热度 " + heat,
                url, item.path("ClusterIdStr").asString(""), list.size() + 1, true));
        }
        return list;
    }

    /** B 站热搜：data.trending.list 的 keyword/show_name/heat_score；链接按关键词构造站内搜索。视频条目无正文，只作线索。 */
    public static List<NewsFeedItem> parseBilibili(byte[] body) {
        JsonNode root = json(body, "B站热搜");
        int code = root.path("code").asInt(0);
        // -412/-403 是 B 站风控与权限拒绝，先归因成 blocked，别让调用方以为是空数据。
        if (code == -412 || code == -403) {
            throw new HeronBusinessException(502, "news_source_blocked",
                "B站热搜被风控拦截(" + code + "): " + root.path("message").asString(""));
        }
        if (code != 0) {
            throw new HeronBusinessException(502, "news_source_platform",
                "B站热搜接口返回错误: " + root.path("message").asString(""));
        }
        JsonNode items = root.path("data").path("trending").path("list");
        if (!items.isArray()) {
            throw new HeronBusinessException(502, "news_source_body", "B站热搜缺少 trending.list");
        }
        List<NewsFeedItem> list = new ArrayList<>();
        for (JsonNode item : items) {
            if (list.size() >= MAX_ITEMS) break;
            String keyword = firstNonBlank(item.path("show_name").asString(""), item.path("keyword").asString(""));
            if (keyword.isEmpty()) continue;
            String heat = numberText(item.path("heat_score"));
            String url = "https://search.bilibili.com/all?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            list.add(new NewsFeedItem(truncate(keyword), heat.isEmpty() ? "B站热搜" : "热度 " + heat,
                url, keyword, list.size() + 1, true));
        }
        return list;
    }

    /** 澎湃热榜：data.hotNews[] 的 name/nodeInfo/contId；link 为空时按 contId 拼详情地址。详情页服务端渲染，允许正文进解说线。 */
    public static List<NewsFeedItem> parseThepaper(byte[] body) {
        JsonNode root = json(body, "澎湃热榜");
        if (root.path("resultCode").asInt(0) != 1) {
            throw new HeronBusinessException(502, "news_source_platform",
                "澎湃热榜接口返回异常: " + root.path("resultMsg").asString(""));
        }
        JsonNode items = root.path("data").path("hotNews");
        if (!items.isArray()) {
            throw new HeronBusinessException(502, "news_source_body", "澎湃热榜缺少 hotNews");
        }
        List<NewsFeedItem> list = new ArrayList<>();
        for (JsonNode item : items) {
            if (list.size() >= MAX_ITEMS) break;
            String title = item.path("name").asString("").strip();
            String contId = item.path("contId").asString("").strip();
            String url = item.path("link").asString("").strip();
            // 只认站内详情链接；分享链或移动域一律按 contId 重建，避免正文抓取撞白名单。
            if (!url.startsWith("https://www.thepaper.cn/") && !contId.isEmpty()) {
                url = "https://www.thepaper.cn/newsDetail_forward_" + contId;
            }
            if (title.isEmpty() || url.isEmpty()) continue;
            String section = item.path("nodeInfo").path("name").asString("").strip();
            list.add(new NewsFeedItem(truncate(title), section.isEmpty() ? "澎湃热榜" : section,
                url, contId, list.size() + 1, false));
        }
        return list;
    }

    /** 知乎热榜：data[].target 的 title/excerpt/url；接口域地址换成可阅读的问题页。正文要登录，只作线索。 */
    public static List<NewsFeedItem> parseZhihu(byte[] body) {
        JsonNode items = json(body, "知乎热榜").path("data");
        if (!items.isArray()) {
            throw new HeronBusinessException(502, "news_source_body", "知乎热榜缺少 data 数组");
        }
        List<NewsFeedItem> list = new ArrayList<>();
        for (JsonNode item : items) {
            if (list.size() >= MAX_ITEMS) break;
            JsonNode target = item.path("target");
            String title = target.path("title").asString("").strip();
            String id = target.path("id").asString("").strip();
            String url = questionUrl(target.path("url").asString("").strip(), id);
            if (title.isEmpty() || url.isEmpty()) continue;
            String excerpt = target.path("excerpt").asString("").strip();
            list.add(new NewsFeedItem(truncate(title), excerpt.isEmpty() ? "知乎热榜" : excerpt,
                url, id, list.size() + 1, true));
        }
        return list;
    }

    /** 头条链接带着大段统计参数，只保留身份路径，避免同一话题因参数变化被当成新条目。 */
    private static String identityUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.strip());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.isEmpty()) return value.strip();
            return uri.getScheme() + "://" + uri.getAuthority() + path;
        } catch (RuntimeException e) {
            return value.strip();
        }
    }

    /** api.zhihu.com 是接口域，证据链接要换成能打开的问题页；其余形态原样保留。 */
    private static String questionUrl(String raw, String id) {
        String prefix = "https://api.zhihu.com/questions/";
        if (raw.startsWith(prefix)) {
            return "https://www.zhihu.com/question/" + raw.substring(prefix.length());
        }
        if (!raw.isEmpty()) return raw;
        return id.isEmpty() ? "" : "https://www.zhihu.com/question/" + id;
    }

    /** 数值字段转文本：Jackson 对数字节点可能给出空串，取不到就退回节点字面量。 */
    private static String numberText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String value = node.asString("");
        return value.isBlank() ? node.toString() : value;
    }

    /** NewsNow 热榜：外壳 status/id/updatedTime/items[]，名次优先取 URL 的 band_rank，缺失时按数组下标。 */
    public static List<NewsFeedItem> parseNewsNow(byte[] body, String platformId, boolean topicOnly) {
        JsonNode root;
        try {
            root = JSON.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(502, "news_source_body", "NewsNow 返回不是 JSON: " + platformId);
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            throw new HeronBusinessException(502, "news_source_platform", "NewsNow 未返回条目列表: " + platformId);
        }
        List<NewsFeedItem> list = new ArrayList<>();
        for (JsonNode item : items) {
            if (list.size() >= MAX_ITEMS) break;
            String title = item.path("title").asString("").strip();
            String rawUrl = firstNonBlank(item.path("url").asString(""), item.path("mobileUrl").asString(""));
            String url = cleanNewsNowUrl(rawUrl);
            if (title.isEmpty() || url == null || url.isBlank()) continue;
            String summary = item.path("extra").path("info").asString("").strip();
            int rank = rankFromNewsNowUrl(rawUrl, list.size() + 1);
            list.add(new NewsFeedItem(truncate(title), summary, url, item.path("id").asString(""), rank, topicOnly));
        }
        return list;
    }

    /** 微博等平台把名次放在 band_rank 里；排名一变链接就变，落库前必须剥掉，避免同一话题重复建候选。 */
    private static String cleanNewsNowUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String url = value.strip();
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) return url;
            StringBuilder kept = new StringBuilder();
            for (String pair : query.split("&")) {
                if (pair.isBlank()) continue;
                int at = pair.indexOf('=');
                String key = at > 0 ? pair.substring(0, at) : pair;
                if ("band_rank".equalsIgnoreCase(key) || "refer".equalsIgnoreCase(key) || "t".equalsIgnoreCase(key)) continue;
                if (!kept.isEmpty()) kept.append('&');
                kept.append(pair);
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return uri.getScheme() + "://" + uri.getAuthority() + path + (kept.isEmpty() ? "" : "?" + kept);
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static int rankFromNewsNowUrl(String url, int fallback) {
        if (url == null) return fallback;
        var matcher = BAND_RANK.matcher(url);
        if (!matcher.find()) return fallback;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String truncate(String value) {
        int total = value.codePointCount(0, value.length());
        return total <= TITLE_CODE_POINTS ? value : value.substring(0, value.offsetByCodePoints(0, TITLE_CODE_POINTS));
    }

    private static String child(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : text(nodes.item(0)).strip();
    }

    private static String attribute(Element parent, String tag, String name) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        Node item = nodes.item(0).getAttributes().getNamedItem(name);
        return item == null ? "" : item.getNodeValue().strip();
    }

    private static String text(Node node) {
        return node.getTextContent() == null ? "" : node.getTextContent();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private static HeronBusinessException tooLarge(String message) {
        return new HeronBusinessException(502, "news_source_too_large", message);
    }
}
