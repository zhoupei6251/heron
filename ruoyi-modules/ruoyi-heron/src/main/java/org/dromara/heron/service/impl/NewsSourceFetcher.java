package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsSource;
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
import java.util.zip.GZIPInputStream;

/**
 * 来源抓取：白名单→解析→地址策略→单次出网逐跳执行，禁自动重定向、自动重试与编码猜测。
 */
@Service
@RequiredArgsConstructor
public class NewsSourceFetcher {

    static final int MAX_REDIRECTS = 3;
    static final int MAX_RAW_BYTES = 1 << 20;
    static final int MAX_TEXT_BYTES = 2 << 20;
    static final int MAX_ITEMS = 200;
    static final int ARTICLE_CODE_POINTS = 12_000;
    private static final int TITLE_CODE_POINTS = 80;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NewsTransport transport;
    private final NewsTransport.Resolver resolver;
    private final HeronNewsProperties properties;

    public List<NewsFeedItem> fetch(HeronNewsSource source) {
        NewsTransport.Response response = request(NewsUrlPolicy.normalize(source.getUrl()));
        byte[] body = decode(response);
        return switch (source.getSourceType()) {
            case "rss", "atom" -> parseXml(body);
            case "text" -> parseText(body);
            case "weibo" -> parseWeibo(body);
            case "baidu" -> parseBaidu(body);
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

    /** 微博热搜：data.realtime[] 的 word/word_scheme；条目链接指向站内搜索。 */
    static List<NewsFeedItem> parseWeibo(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(502, "news_source_body", "微博热榜不是 JSON");
        }
        JsonNode items = root.path("data").path("realtime");
        List<NewsFeedItem> list = new ArrayList<>();
        if (!items.isArray()) return list;
        for (JsonNode item : items) {
            if (list.size() >= MAX_ITEMS) break;
            String word = item.path("word").asString("").strip();
            if (word.isEmpty()) continue;
            String scheme = item.path("word_scheme").asString(word);
            String url = "https://s.weibo.com/weibo?q=" + URLEncoder.encode(scheme, StandardCharsets.UTF_8);
            list.add(new NewsFeedItem(truncate(word), "微博热搜", url, word, list.size() + 1));
        }
        return list;
    }

    /** 百度热搜：HTML 里的 s-data JSON，取 component=hotList 的 content[]。 */
    static List<NewsFeedItem> parseBaidu(byte[] body) {
        String html = new String(body, StandardCharsets.UTF_8);
        int start = html.indexOf("s-data:");
        int end = start < 0 ? -1 : html.indexOf("-->", start);
        if (start < 0 || end <= start) {
            throw new HeronBusinessException(502, "news_source_body", "百度热榜缺少 s-data");
        }
        JsonNode root;
        try {
            root = JSON.readTree(html.substring(start + "s-data:".length(), end).strip());
        } catch (RuntimeException e) {
            throw new HeronBusinessException(502, "news_source_body", "百度热榜 s-data 不是 JSON");
        }
        JsonNode cards = root.path("data").path("cards");
        List<NewsFeedItem> list = new ArrayList<>();
        if (!cards.isArray()) return list;
        for (JsonNode card : cards) {
            if (!"hotList".equals(card.path("component").asString(""))) continue;
            for (JsonNode item : card.path("content")) {
                if (list.size() >= MAX_ITEMS) break;
                String word = item.path("word").asString("").strip();
                String url = item.path("url").asString("");
                if (word.isEmpty() || url.isEmpty()) continue;
                list.add(new NewsFeedItem(truncate(word), "百度热搜", url, item.path("rawUrl").asString(""), list.size() + 1));
            }
        }
        return list;
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
