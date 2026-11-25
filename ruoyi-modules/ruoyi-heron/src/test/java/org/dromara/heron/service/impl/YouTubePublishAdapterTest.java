package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.sun.net.httpserver.HttpServer;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.service.IPublishAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("YouTube 发布出口：独立关闭与凭据闸")
class YouTubePublishAdapterTest {
    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final URI START_URI = URI.create("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status");

    @Test
    @DisplayName("默认仍选沙箱，YouTube 独立开关关闭且不预置凭据")
    void defaultsKeepYouTubeInert() {
        HeronNewsProperties properties = new HeronNewsProperties();
        assertEquals("sandbox", properties.getPublishAdapter());
        assertFalse(properties.isYoutubeEnabled());
        assertNull(properties.getYoutubeClientId());
        assertNull(properties.getYoutubeClientSecret());
        assertNull(properties.getYoutubeRefreshToken());
        assertEquals("private", properties.getYoutubePrivacyStatus());
    }

    @Test
    @DisplayName("开关未打开时明确拒绝，不访问网络")
    void disabledOutletIsRejectedExplicitly() {
        HeronNewsProperties properties = credentials();
        var error = assertThrows(HeronBusinessException.class,
            () -> new YouTubePublishAdapter(properties).validate());
        assertEquals(503, error.getStatus());
        assertEquals("youtube_disabled", error.getCode());
        assertTrue(error.getMessage().contains("youtube-enabled"));
    }

    @Test
    @DisplayName("逐项缺失凭据都给出对应配置键")
    void missingCredentialsNameTheRequiredKeys() {
        assertCredentialError("youtube-client-id", properties -> properties.setYoutubeClientId(null));
        assertCredentialError("youtube-client-secret", properties -> properties.setYoutubeClientSecret(" "));
        assertCredentialError("youtube-refresh-token", properties -> properties.setYoutubeRefreshToken(null));
    }

    private static void assertCredentialError(String key, java.util.function.Consumer<HeronNewsProperties> remove) {
        HeronNewsProperties properties = credentials();
        properties.setYoutubeEnabled(true);
        remove.accept(properties);
        var error = assertThrows(HeronBusinessException.class,
            () -> new YouTubePublishAdapter(properties).validate());
        assertEquals(503, error.getStatus());
        assertEquals("youtube_credentials_missing", error.getCode());
        assertTrue(error.getMessage().contains(key));
    }

    @Test
    @DisplayName("按 OAuth 刷新与 resumable 协议上传，并从视频 ID 生成回执")
    void publishesThroughResumableProtocol(@TempDir Path temp) throws Exception {
        byte[] video = "local-video-contract".getBytes(StandardCharsets.UTF_8);
        Path file = temp.resolve("final.mp4");
        Files.write(file, video);
        HeronNewsProperties properties = credentials();
        properties.setYoutubeEnabled(true);
        properties.setYoutubeClientId("client+id");
        properties.setYoutubeClientSecret("secret&value");
        properties.setYoutubeRefreshToken("refresh=token");
        AtomicReference<String> tokenForm = new AtomicReference<>();
        AtomicReference<String> metadata = new AtomicReference<>();
        AtomicReference<String> uploadHeaders = new AtomicReference<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json", "{\"access_token\":\"local-access-token\"}");
        });
        server.createContext("/upload", exchange -> {
            metadata.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Files.writeString(file, "concurrently-replaced");
            exchange.getResponseHeaders().set("Location", base(server) + "/media");
            respond(exchange, 200, "application/json", "{}");
        });
        server.createContext("/media", exchange -> {
            uploaded.set(exchange.getRequestBody().readAllBytes());
            uploadHeaders.set(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("Authorization")
                + "|" + exchange.getRequestHeaders().getFirst("Content-Range")
                + "|" + exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 200, "application/json", "{\"id\":\"aBcDeFgHiJk\"}");
        });
        server.start();
        try {
            var adapter = new YouTubePublishAdapter(properties, HttpClient.newHttpClient(),
                URI.create(base(server) + "/token"), URI.create(base(server) + "/upload?uploadType=resumable&part=snippet,status"));
            LocalDateTime requested = LocalDateTime.of(2026, 11, 24, 10, 30, 15, 123456789);
            var receipt = adapter.publish(new PublishRequest(1, 2, "  标题  ", DigestUtil.sha256Hex(video), requested, file,
                "frozen-channel", "素材署名：\n示例作者 / CC_BY"));
            assertEquals("youtube", receipt.source());
            assertEquals("frozen-channel", receipt.account());
            assertEquals("https://www.youtube.com/watch?v=aBcDeFgHiJk", receipt.workUrl());
            assertEquals(requested.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), receipt.publishedAt());
            Map<String, String> form = form(tokenForm.get());
            assertEquals("refresh_token", form.get("grant_type"));
            assertEquals("client+id", form.get("client_id"));
            assertEquals("secret&value", form.get("client_secret"));
            assertEquals("refresh=token", form.get("refresh_token"));
            assertTrue(metadata.get().contains("\"title\":\"标题\""));
            assertTrue(metadata.get().contains("素材署名") && metadata.get().contains("示例作者"));
            assertTrue(metadata.get().contains("\"privacyStatus\":\"private\""));
            assertTrue(metadata.get().contains("\"selfDeclaredMadeForKids\":false"));
            assertEquals("PUT|Bearer local-access-token|bytes 0-" + (video.length - 1) + "/" + video.length + "|video/mp4", uploadHeaders.get());
            assertArrayEquals(video, uploaded.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("同一文件 SHA-256 与审核指纹不符时，刷新令牌前拒绝且不上传")
    void rejectsHashMismatchBeforeNetwork(@TempDir Path temp) throws Exception {
        Files.write(temp.resolve("final.mp4"), new byte[] {1});
        List<HttpRequest> requests = new ArrayList<>();
        HeronNewsProperties properties = credentials();
        properties.setYoutubeEnabled(true);
        var adapter = new YouTubePublishAdapter(properties, recordingClient(requests,
            "https://www.googleapis.com/upload/youtube/v3/videos?upload_id=trusted"), TOKEN_URI, START_URI);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> adapter.publish(
            new PublishRequest(1, 2, "标题", "f".repeat(64), LocalDateTime.now(), temp.resolve("final.mp4"))));

        assertEquals("publish_hash", error.getCode());
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeUploadLocations")
    @DisplayName("非法上传会话地址在 Bearer 发往该地址前拒绝，且不请求上传端点")
    void rejectsUnsafeUploadLocationBeforeSendingBearer(String reason, String location, @TempDir Path temp) throws Exception {
        Files.write(temp.resolve("final.mp4"), new byte[] {1});
        HeronNewsProperties properties = credentials();
        properties.setYoutubeEnabled(true);
        List<HttpRequest> requests = new ArrayList<>();
        var adapter = new YouTubePublishAdapter(properties, recordingClient(requests, location), TOKEN_URI, START_URI);

        var error = assertThrows(HeronBusinessException.class, () -> adapter.publish(
            new PublishRequest(1, 2, "标题", DigestUtil.sha256Hex(new byte[] {1}), LocalDateTime.now(), temp.resolve("final.mp4"))));

        assertEquals(502, error.getStatus());
        assertEquals("youtube_upload_location_invalid", error.getCode());
        assertEquals(List.of(TOKEN_URI, START_URI), requests.stream().map(HttpRequest::uri).toList(),
            reason + "：只应刷新令牌并创建官方上传会话，不能向 Location 发送第三个请求");
    }

    @Test
    @DisplayName("合法 Google HTTPS 上传会话地址被接受，并只向该地址发送 Bearer")
    void acceptsOfficialGoogleHttpsUploadLocation(@TempDir Path temp) throws Exception {
        Files.write(temp.resolve("final.mp4"), new byte[] {1});
        HeronNewsProperties properties = credentials();
        properties.setYoutubeEnabled(true);
        String location = "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status&upload_id=trusted";
        List<HttpRequest> requests = new ArrayList<>();
        var adapter = new YouTubePublishAdapter(properties, recordingClient(requests, location), TOKEN_URI, START_URI);

        var receipt = adapter.publish(new PublishRequest(1, 2, "标题", DigestUtil.sha256Hex(new byte[] {1}),
            LocalDateTime.now(), temp.resolve("final.mp4")));

        assertEquals("https://www.youtube.com/watch?v=aBcDeFgHiJk", receipt.workUrl());
        assertEquals(3, requests.size());
        assertEquals(URI.create(location), requests.get(2).uri());
        assertEquals("PUT", requests.get(2).method());
        assertEquals("Bearer local-access-token", requests.get(2).headers().firstValue("Authorization").orElseThrow());
    }

    private static Stream<Arguments> unsafeUploadLocations() {
        return Stream.of(
            Arguments.of("恶意后缀域名", "https://www.googleapis.com.evil.test/media"),
            Arguments.of("恶意前缀域名", "https://evilgoogleapis.com/media"),
            Arguments.of("HTTP", "http://www.googleapis.com/media"),
            Arguments.of("非默认端口", "https://www.googleapis.com:8443/media"),
            Arguments.of("userinfo 伪装宿主", "https://www.googleapis.com@evil.test/media"),
            Arguments.of("合法宿主带 userinfo", "https://user:pass@www.googleapis.com/media"),
            Arguments.of("fragment", "https://www.googleapis.com/media#fragment"));
    }

    private static HttpClient recordingClient(List<HttpRequest> requests, String location) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return switch (requests.size()) {
                case 1 -> response(200, "{\"access_token\":\"local-access-token\"}", null);
                case 2 -> response(200, "{}", location);
                case 3 -> response(200, "{\"id\":\"aBcDeFgHiJk\"}", null);
                default -> throw new AssertionError("不应发送更多请求: " + requests);
            };
        });
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body, String location) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        HttpHeaders headers = HttpHeaders.of(location == null ? Map.of() : Map.of("Location", List.of(location)),
            (key, value) -> true);
        when(response.headers()).thenReturn(headers);
        return response;
    }

    @Test
    @DisplayName("第三方错误响应不进入异常，避免回显凭据")
    void tokenFailureDoesNotExposeCredentials(@TempDir Path temp) throws Exception {
        Files.write(temp.resolve("final.mp4"), new byte[] {1});
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange, 401, "application/json",
            "{\"error\":\"secret&value refresh=token local-access-token\"}"));
        server.start();
        try {
            HeronNewsProperties properties = credentials();
            properties.setYoutubeEnabled(true);
            var adapter = new YouTubePublishAdapter(properties, HttpClient.newHttpClient(),
                URI.create(base(server) + "/token"), URI.create(base(server) + "/upload"));
            var error = assertThrows(HeronBusinessException.class, () -> adapter.publish(
                new PublishRequest(1, 2, "标题", DigestUtil.sha256Hex(new byte[] {1}), LocalDateTime.now(), temp.resolve("final.mp4"))));
            assertEquals("youtube_token_failed", error.getCode());
            assertTrue(error.getMessage().contains("401"));
            assertFalse(error.getMessage().contains("secret&value"));
            assertFalse(error.getMessage().contains("refresh=token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("只接受规范 YouTube watch 回执，拒绝伪装域名与额外参数")
    void receiptUrlIsRestrictedToYouTubeVideoIds() {
        assertTrue(YouTubePublishAdapter.youtubeUrl("https://www.youtube.com/watch?v=aBcDeFgHiJk"));
        assertFalse(YouTubePublishAdapter.youtubeUrl("https://www.youtube.com/watch?v=short"));
        assertFalse(YouTubePublishAdapter.youtubeUrl("https://www.youtube.com/watch?v=aBcDeFgHiJk&t=3"));
        assertFalse(YouTubePublishAdapter.youtubeUrl("https://youtube.com.evil.test/watch?v=aBcDeFgHiJk"));
        assertFalse(YouTubePublishAdapter.youtubeUrl("https://www.douyin.com/video/7400000000000000000"));
    }

    @Test
    @DisplayName("隐私状态白名单与属性 toString 都不泄露凭据")
    void privacyAndPropertyLoggingAreSafe() {
        HeronNewsProperties properties = credentials();
        properties.setYoutubeEnabled(true);
        properties.setYoutubePrivacyStatus("PUBLIC");
        assertEquals("youtube_privacy_invalid", assertThrows(HeronBusinessException.class,
            () -> new YouTubePublishAdapter(properties).validate()).getCode());
        properties.setYoutubePrivacyStatus("private");
        assertDoesNotThrow(() -> new YouTubePublishAdapter(properties).validate());
        assertFalse(properties.toString().contains("test-client-secret"));
        assertFalse(properties.toString().contains("test-refresh-token"));
    }

    @Test
    @DisplayName("publish_source 选择互斥，YouTube 开关不影响抖音与默认沙箱")
    void outletSelectionRemainsIndependent() {
        var runner = new ApplicationContextRunner().withUserConfiguration(AdapterConfig.class);
        runner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(IPublishAdapter.class)
                .hasSingleBean(SandboxPublishAdapter.class);
        });
        runner.withPropertyValues("heron.news.youtube-enabled=true").run(context ->
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(SandboxPublishAdapter.class));
        runner.withPropertyValues("heron.news.publish-adapter=youtube").run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(IPublishAdapter.class)
                .hasSingleBean(YouTubePublishAdapter.class);
            var error = assertThrows(HeronBusinessException.class,
                () -> context.getBean(YouTubePublishAdapter.class).validate());
            assertEquals("youtube_disabled", error.getCode());
        });
        runner.withPropertyValues("heron.news.publish-adapter=douyin-cli", "heron.news.youtube-enabled=true")
            .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                .hasSingleBean(DouyinCliPublishAdapter.class).doesNotHaveBean(YouTubePublishAdapter.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HeronNewsProperties.class)
    @Import({SandboxPublishAdapter.class, DouyinCliPublishAdapter.class, YouTubePublishAdapter.class})
    static class AdapterConfig { }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> form(String body) {
        return java.util.Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2))
            .collect(java.util.stream.Collectors.toMap(parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }

    private static HeronNewsProperties credentials() {
        HeronNewsProperties properties = new HeronNewsProperties();
        properties.setYoutubeClientId("test-client-id");
        properties.setYoutubeClientSecret("test-client-secret");
        properties.setYoutubeRefreshToken("test-refresh-token");
        return properties;
    }
}
