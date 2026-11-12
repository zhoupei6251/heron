package org.dromara.heron.service.impl;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.dromara.heron.domain.HeronBusinessException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出网传输：一次调用只连接策略刚验证过的那个地址。
 * 自动重定向、自动重试与自动解压全部关闭，逐跳校验与体积上限由 NewsSourceFetcher 负责。
 */
public final class ApacheNewsTransport implements NewsTransport, NewsTransport.Resolver, AutoCloseable {

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(10);
    private static final ThreadLocal<Map<String, InetAddress>> PINS = ThreadLocal.withInitial(HashMap::new);

    private final PoolingHttpClientConnectionManager manager;
    private final CloseableHttpClient client;

    public ApacheNewsTransport() {
        DnsResolver pinned = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress address = PINS.get().get(host);
                if (address == null) throw new UnknownHostException("来源地址未经策略校验，拒绝解析: " + host);
                return new InetAddress[] {address};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        this.manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned).build();
        this.client = HttpClients.custom().setConnectionManager(manager)
            .disableRedirectHandling().disableAutomaticRetries().disableContentCompression()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT).setResponseTimeout(RESPONSE_TIMEOUT)
                .setRedirectsEnabled(false).build())
            .build();
    }

    @Override
    public List<InetAddress> resolve(String host) throws IOException {
        return List.of(InetAddress.getAllByName(host));
    }

    @Override
    public NewsTransport.Response get(NewsTransport.Request request) throws IOException {
        String host = NewsUrlPolicy.hostOf(request.url());
        PINS.get().put(host, request.pinned());
        HttpGet get = new HttpGet(request.url());
        // 热榜站点按 UA + 同源 Referer 做基础风控；RSS 源多这两个头无副作用。
        get.setHeader("User-Agent", "Mozilla/5.0");
        get.setHeader("Referer", "https://" + host + "/");
        try {
            return client.execute(get, response -> {
                Header location = response.getFirstHeader("Location");
                Header encoding = response.getFirstHeader("Content-Encoding");
                Header contentType = response.getFirstHeader("Content-Type");
                try {
                    return new NewsTransport.Response(response.getCode(),
                        location == null ? null : location.getValue(),
                        encoding == null ? null : encoding.getValue(),
                        contentType == null ? null : contentType.getValue(), read(response.getEntity()));
                } finally {
                    EntityUtils.consume(response.getEntity());
                }
            });
        } finally {
            PINS.get().clear();
        }
    }

    private byte[] read(HttpEntity entity) throws IOException {
        if (entity == null) return new byte[0];
        if (entity.getContentLength() > NewsSourceFetcher.MAX_RAW_BYTES) throw tooLarge();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (var content = entity.getContent()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = content.read(chunk)) > 0) {
                if (buffer.size() + read > NewsSourceFetcher.MAX_RAW_BYTES) throw tooLarge();
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }

    private static HeronBusinessException tooLarge() {
        return new HeronBusinessException(502, "news_source_too_large", "来源响应体超过 1 MiB 上限");
    }

    @Override
    public void close() throws IOException {
        client.close();
        manager.close();
    }
}
