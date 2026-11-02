package org.dromara.heron.support;

import org.dromara.heron.service.impl.NewsTransport;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * N2 采集夹具：脚本化应答与固定公网解析，测试期间不产生任何真实出网。
 */
public class HeronNewsTestFixtures implements NewsTransport, NewsTransport.Resolver {

    private final Map<String, NewsTransport.Response> replies = new LinkedHashMap<>();
    private final Set<String> failures = new LinkedHashSet<>();
    private final Map<String, InetAddress> addresses = new LinkedHashMap<>();
    private NewsTransport.Response wildcard;
    private int connections;

    public void reply(String url, int status, String location, String encoding, String body) {
        replies.put(url, new NewsTransport.Response(status, location, encoding, body.getBytes(StandardCharsets.UTF_8)));
    }

    public void fail(String url) {
        failures.add(url);
    }

    /** 兜底应答：只用于不关心内容、只关心租约与调度事实的用例。 */
    public void replyAll(String body) {
        wildcard = new NewsTransport.Response(200, null, null, body.getBytes(StandardCharsets.UTF_8));
    }

    public int connections() {
        return connections;
    }

    public void reset() {
        replies.clear();
        failures.clear();
        addresses.clear();
        wildcard = null;
        connections = 0;
    }

    @Override
    public synchronized List<InetAddress> resolve(String host) throws IOException {
        InetAddress known = addresses.get(host);
        if (known != null) return List.of(known);
        // TEST-NET-3 段既公网可路由又不会撞到真实地址；每主机固定，解析结果可复现。
        InetAddress address = InetAddress.getByName("203.0.113." + (addresses.size() + 10));
        addresses.put(host, address);
        return List.of(address);
    }

    @Override
    public NewsTransport.Response get(NewsTransport.Request request) throws IOException {
        connections++;
        String url = request.url().toString();
        if (failures.contains(url)) throw new IOException("夹具拒绝连接: " + url);
        NewsTransport.Response reply = replies.getOrDefault(url, wildcard);
        if (reply == null) throw new IOException("未登记的出网目标: " + url);
        return reply;
    }
}
