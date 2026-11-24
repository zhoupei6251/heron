package org.dromara.heron.service.impl;

import com.sun.net.httpserver.HttpServer;
import org.dromara.heron.config.HeronNewsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("参考视频 HTTP 传输：返回后响应流仍可读")
class ApacheReferenceVideoTransportTest {

    @Test
    void responseBodyRemainsReadableAfterHandlerReturns() throws Exception {
        byte[] body = "real-mp4-bytes".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/video", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try (ApacheReferenceVideoTransport transport = new ApacheReferenceVideoTransport(new HeronNewsProperties())) {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/video");
            try (ReferenceVideoTransport.Response response = transport.open(
                new ReferenceVideoTransport.Request(url, InetAddress.getLoopbackAddress()))) {
                assertEquals(200, response.status());
                assertEquals("video/mp4", response.contentType());
                assertArrayEquals(body, response.body().readAllBytes());
            }
        } finally {
            server.stop(0);
        }
    }
}
