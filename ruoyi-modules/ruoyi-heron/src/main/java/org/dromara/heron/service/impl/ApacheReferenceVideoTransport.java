package org.dromara.heron.service.impl;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.dromara.heron.config.HeronNewsProperties;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/** 视频下载传输：关闭自动重定向与重试，DNS 钉定由调用方给定的公网地址。 */
final class ApacheReferenceVideoTransport implements ReferenceVideoTransport {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DOUYIN_REFERER = "https://www.douyin.com/";

    private final ThreadLocal<Map<String, InetAddress>> pins = ThreadLocal.withInitial(HashMap::new);
    private final PoolingHttpClientConnectionManager manager;
    private final CloseableHttpClient client;

    ApacheReferenceVideoTransport(HeronNewsProperties properties) {
        DnsResolver pinned = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress address = pins.get().get(host);
                if (address == null) throw new UnknownHostException("来源地址未经策略校验: " + host);
                return new InetAddress[] {address};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        this.manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned).build();
        int connectSeconds = Math.max(1, properties.getReferenceConnectTimeoutSeconds());
        int readSeconds = Math.max(1, properties.getReferenceReadTimeoutSeconds());
        this.client = HttpClients.custom().setConnectionManager(manager)
            .disableRedirectHandling().disableAutomaticRetries().disableContentCompression()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectSeconds))
                .setResponseTimeout(Timeout.ofSeconds(readSeconds))
                .setRedirectsEnabled(false).build())
            .build();
    }

    @Override
    public Response open(Request request) throws IOException {
        String host = NewsUrlPolicy.hostOf(request.url());
        pins.get().put(host, request.pinned());
        HttpGet get = new HttpGet(request.url());
        get.setHeader("User-Agent", USER_AGENT);
        get.setHeader("Referer", DOUYIN_REFERER);
        try {
            CloseableHttpResponse response = client.execute(get, HttpClientContext.create());
            Header location = response.getFirstHeader("Location");
            Header contentType = response.getFirstHeader("Content-Type");
            int status = response.getCode();
            String locationValue = location == null ? null : location.getValue();
            String contentTypeValue = contentType == null ? null : contentType.getValue();
            var entity = response.getEntity();
            if (entity == null) {
                response.close();
                return new Response(status, locationValue, contentTypeValue, -1, null);
            }
            InputStream body;
            try {
                body = entity.getContent();
            } catch (IOException e) {
                response.close();
                throw e;
            }
            InputStream owned = new FilterInputStream(body) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
            return new Response(response.getCode(), location == null ? null : location.getValue(),
                contentType == null ? null : contentType.getValue(), entity.getContentLength(), owned);
        } finally {
            pins.get().clear();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            client.close();
        } finally {
            manager.close();
        }
    }
}
