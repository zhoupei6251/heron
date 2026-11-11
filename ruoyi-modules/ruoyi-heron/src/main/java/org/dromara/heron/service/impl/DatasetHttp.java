package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 数据适配器共用的出网入口：单次 GET、跟随 301、体积上限、失败带源返回码。 */
final class DatasetHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 适配器按实体实例化，客户端只留一套；HttpClient 本身线程安全。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

    private DatasetHttp() { }

    static HttpClient client() {
        return CLIENT;
    }

    static String get(HttpClient http, String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw invalid("源返回 HTTP " + response.statusCode());
            }
            if (response.body().length > MAX_BYTES) {
                throw invalid("源响应超过 " + MAX_BYTES + " 字节");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw invalid("抓取被中断");
        } catch (java.io.IOException e) {
            throw invalid("抓取失败：" + e.getMessage());
        }
    }

    static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(502, "dataset_invalid", reason);
    }
}
