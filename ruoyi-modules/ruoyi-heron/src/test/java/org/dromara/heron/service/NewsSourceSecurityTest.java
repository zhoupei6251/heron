package org.dromara.heron.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.service.impl.NewsFeedItem;
import org.dromara.heron.service.impl.NewsSourceFetcher;
import org.dromara.heron.service.impl.NewsTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** N2 来源采集安全边界：白名单、地址策略、逐跳出网、体积与解析。 */
@DisplayName("N2 新闻来源采集安全边界")
class NewsSourceSecurityTest {

    private FakeTransport transport;
    private FakeResolver resolver;
    private HeronNewsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HeronNewsProperties();
        properties.setAllowedHosts(new ArrayList<>(List.of("feed.example.test", "other.example.test",
            IDN.toASCII("例え") + ".example")));
        transport = new FakeTransport();
        resolver = new FakeResolver();
    }

    private NewsSourceFetcher fetcher() {
        return new NewsSourceFetcher(transport, resolver, properties);
    }

    private HeronNewsSource source(String url, String type) {
        HeronNewsSource source = new HeronNewsSource();
        source.setSourceId(7L);
        source.setName("测试来源");
        source.setUrl(url);
        source.setSourceType(type);
        source.setIntervalSeconds(600);
        source.setEnabled("0");
        return source;
    }

    private static byte[] fixture(String name) throws IOException {
        try (var input = NewsSourceSecurityTest.class.getResourceAsStream("/news-feeds/" + name)) {
            assertNotNull(input, "缺少夹具 " + name);
            return input.readAllBytes();
        }
    }

    private static String rssBody() throws IOException {
        return new String(fixture("rss.xml"), StandardCharsets.UTF_8);
    }

    private static String rssWithItems(int count) {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            items.append("<item><title>标题").append(i).append("</title><link>https://feed.example.test/i")
                .append(i).append("</link><guid>g").append(i).append("</guid><description>摘要").append(i)
                .append("</description></item>");
        }
        return "<rss version=\"2.0\"><channel><title>大频道</title>" + items + "</channel></rss>";
    }

    static List<Map<String, Object>> redirectCases() throws IOException {
        // 纯安全边界用例不启动 Spring，所以直接建 mapper，不能借需要上下文的 JsonUtils。
        return JsonMapper.builder().build().readValue(fixture("redirect-cases.json"),
            new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("redirectCases")
    void redirectChainIsCheckedHopByHop(Map<String, Object> c) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> hops = (List<String>) c.get("hops");
        String expect = (String) c.get("expect");
        for (int i = 0; i < hops.size() - 1; i++) {
            transport.reply(hops.get(i), 302, hops.get(i + 1), null, new byte[0]);
        }
        transport.reply(hops.get(hops.size() - 1), 200, null, null, rssBody().getBytes(StandardCharsets.UTF_8));
        if ("ok".equals(expect)) {
            assertEquals(2, fetcher().fetch(source(hops.get(0), "rss")).size(), () -> c.get("name") + " 应正常解析两条");
        } else {
            HeronBusinessException e = assertThrows(HeronBusinessException.class,
                () -> fetcher().fetch(source(hops.get(0), "rss")), () -> c.get("name") + " 必须被拒绝");
            assertEquals(expect, e.getCode(), () -> c.get("name") + " 的实际错误码");
        }
        assertEquals(0, transport.privateConnectionCount(), () -> c.get("name") + " 不得向非公网地址出网");
    }

    @Test
    void privateRedirectIsRejectedBeforeConnection() throws Exception {
        transport.reply("https://feed.example.test/rss", 302, "http://127.0.0.1/latest/meta-data/", null, new byte[0]);
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_blocked", e.getCode());
        assertEquals(0, transport.privateConnectionCount());
    }

    @Test
    void dnsRebindingOnANewerHopIsRejectedBeforeConnection() throws Exception {
        transport.reply("https://feed.example.test/rss", 302, "https://feed.example.test/feed2", null, new byte[0]);
        transport.reply("https://feed.example.test/feed2", 200, null, null, rssBody().getBytes(StandardCharsets.UTF_8));
        resolver.rebind("feed.example.test", InetAddress.getByName("127.0.0.1"));
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_blocked", e.getCode());
        assertEquals(0, transport.privateConnectionCount(), "只有首次公网解析允许出网");
        assertEquals(1, transport.connections());
    }

    @Test
    void unicodeHostIsNormalizedToAsciiBeforeWhitelistMatch() throws Exception {
        // 出网目标只登记 ASCII 形式：能命中即证明主机名在白名单比对前已做 IDNA 归一。
        transport.reply("https://" + IDN.toASCII("例え") + ".example/rss", 200, null, null,
            rssBody().getBytes(StandardCharsets.UTF_8));
        assertEquals(2, fetcher().fetch(source("https://例え.example/rss", "rss")).size());
        assertEquals(IDN.toASCII("例え") + ".example", resolver.lastHost);
    }

    @Test
    void emptyAllowListBlocksEveryNetworkSourceBeforeResolution() {
        properties.setAllowedHosts(new ArrayList<>());
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_host", e.getCode());
        assertEquals(0, resolver.lookups());
        assertEquals(0, transport.connections());
    }

    @Test
    void wildcardWhitelistEntryNeverMatchesAnActualHost() {
        properties.setAllowedHosts(new ArrayList<>(List.of("*.example.test")));
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_host", e.getCode());
    }

    @Test
    void gzipBombIsRejectedAtTwoMebibytes() throws Exception {
        byte[] inflated = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(inflated, (byte) 'a');
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(inflated);
        }
        transport.reply("https://feed.example.test/rss", 200, null, "gzip", compressed.toByteArray());
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_too_large", e.getCode());
    }

    @Test
    void oversizedRawBodyIsRejected() throws Exception {
        transport.reply("https://feed.example.test/rss", 200, null, null, new byte[1024 * 1024 + 1]);
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_too_large", e.getCode());
    }

    @Test
    void unknownContentEncodingIsRejectedInsteadOfGuessed() throws Exception {
        transport.reply("https://feed.example.test/rss", 200, null, "br", rssBody().getBytes(StandardCharsets.UTF_8));
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_encoding", e.getCode());
    }

    @Test
    void nonSuccessStatusIsReportedNotParsed() throws Exception {
        transport.reply("https://feed.example.test/rss", 403, null, null, new byte[0]);
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_status", e.getCode());
    }

    @Test
    void xxeFixtureIsRejectedWithoutAnyItemOrExternalRead() throws Exception {
        transport.reply("https://feed.example.test/rss", 200, null, null, fixture("xxe.xml"));
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_xml", e.getCode());
        assertEquals(0, transport.privateConnectionCount(), "外部实体不得产生第二次出网");
    }

    @Test
    void damagedXmlFailsWithSourceReason() throws Exception {
        transport.reply("https://feed.example.test/rss", 200, null, null,
            "<rss><channel><item><title>".getBytes(StandardCharsets.UTF_8));
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_xml", e.getCode());
    }

    @Test
    void rssAndAtomFieldsAreBoundedAndTextIsNeverMarkup() throws Exception {
        transport.reply("https://feed.example.test/rss", 200, null, null, fixture("rss.xml"));
        List<NewsFeedItem> rss = fetcher().fetch(source("https://feed.example.test/rss", "rss"));
        assertEquals(List.of("某地 发布 新规", "某地发布新规 12.5 条"), rss.stream().map(NewsFeedItem::title).toList());
        assertEquals("https://feed.example.test/a1?keep=1", rss.get(0).url(), "跟踪参数出解析层就要剔除，有语义参数保留");
        assertEquals("feed-a1", rss.get(0).itemId());
        assertEquals(List.of(1, 2), rss.stream().map(NewsFeedItem::rank).toList());

        transport.reply("https://other.example.test/atom", 200, null, null, fixture("atom.xml"));
        List<NewsFeedItem> atom = fetcher().fetch(source("https://other.example.test/atom", "atom"));
        assertEquals(1, atom.size(), "空标题条目被跳过而不是写入");
        assertEquals("某地发布新规！", atom.get(0).title());
        assertEquals("https://other.example.test/b1", atom.get(0).url());
        assertEquals("atom-b1", atom.get(0).itemId());

        transport.reply("https://feed.example.test/plain", 200, null, null, fixture("plain.txt"));
        List<NewsFeedItem> plain = fetcher().fetch(source("https://feed.example.test/plain", "text"));
        assertEquals(3, plain.size(), "空行跳过，其余每行一条");
        assertTrue(plain.get(1).title().contains("<h1>"), "纯文本行按字面保存，不执行标记解析");
        assertTrue(plain.get(2).summary().length() > plain.get(2).title().length(), "只截标题不截摘要");
        assertTrue(plain.stream().allMatch(i -> i.url() == null), "纯文本条目不得凭空编造来源链接");
    }

    @Test
    void itemLimitKeepsOnlyTopTwoHundred() throws Exception {
        transport.reply("https://feed.example.test/rss", 200, null, null,
            rssWithItems(250).getBytes(StandardCharsets.UTF_8));
        List<NewsFeedItem> items = fetcher().fetch(source("https://feed.example.test/rss", "rss"));
        assertEquals(200, items.size());
        assertEquals("标题1", items.get(0).title());
        assertEquals("标题200", items.get(199).title());
    }

    @Test
    void transportFailureIsReportedAsUnreachableNotSilentSuccess() {
        transport.fail("https://feed.example.test/rss");
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> fetcher().fetch(source("https://feed.example.test/rss", "rss")));
        assertEquals("news_source_unreachable", e.getCode());
    }

    /** 只记录被要求的出网目标；非公网目标计数用于证明拒绝发生在连接之前。 */
    private static final class FakeTransport implements NewsTransport {
        private final Map<String, NewsTransport.Response> replies = new LinkedHashMap<>();
        private final Map<String, IOException> failures = new LinkedHashMap<>();
        private int connections;
        private int privateConnections;

        void reply(String url, int status, String location, String encoding, byte[] body) {
            replies.put(url, new NewsTransport.Response(status, location, encoding, body));
        }

        void fail(String url) { failures.put(url, new IOException("connection refused")); }

        int connections() { return connections; }

        int privateConnectionCount() { return privateConnections; }

        /** 夹具自己判定，不复用生产策略，避免用被测代码证明被测代码。 */
        private static boolean isPrivate(InetAddress address) {
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress();
        }

        @Override
        public NewsTransport.Response get(NewsTransport.Request request) throws IOException {
            connections++;
            if (isPrivate(request.pinned())) privateConnections++;
            IOException failure = failures.get(request.url().toString());
            if (failure != null) throw failure;
            NewsTransport.Response reply = replies.get(request.url().toString());
            if (reply == null) throw new IOException("未登记的出网目标: " + request.url());
            return reply;
        }
    }

    private static final class FakeResolver implements NewsTransport.Resolver {
        private final Map<String, List<InetAddress>> answers = new LinkedHashMap<>();
        private final Map<String, InetAddress> rebound = new LinkedHashMap<>();
        private final Map<String, Integer> lookupsPerHost = new LinkedHashMap<>();
        private int lookups;
        private String lastHost;

        FakeResolver() {
            try {
                answers.put("feed.example.test", List.of(InetAddress.getByName("203.0.113.10")));
                answers.put("other.example.test", List.of(InetAddress.getByName("203.0.113.11")));
                answers.put(IDN.toASCII("例え") + ".example", List.of(InetAddress.getByName("203.0.113.12")));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        /** 只污染同一主机的第二次解析：首次仍公网，才能证明重绑定发生在后续跳。 */
        void rebind(String host, InetAddress later) { rebound.put(host, later); }

        int lookups() { return lookups; }

        @Override
        public List<InetAddress> resolve(String host) throws IOException {
            lookups++;
            lastHost = host;
            if (rebound.get(host) != null && lookupsPerHost.merge(host, 1, Integer::sum) > 1) {
                return List.of(rebound.get(host));
            }
            List<InetAddress> answer = answers.get(host);
            return answer == null ? List.of(InetAddress.getByName(host)) : answer;
        }
    }
}
