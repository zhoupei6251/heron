package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("参考视频下载：出网闸、体积与文件真实性")
class ReferenceVideoDownloaderTest {

    @TempDir
    Path tempDir;

    private final FakeTransport transport = new FakeTransport();
    private final FakeResolver resolver = new FakeResolver();

    private ReferenceVideoDownloader downloader() {
        HeronNewsProperties properties = new HeronNewsProperties();
        properties.setReferenceMaxBytes(64);
        properties.setReferenceMaxRedirects(2);
        return new ReferenceVideoDownloader(properties, transport, resolver, tempDir);
    }

    @Test
    void downloadsMp4AndReturnsHash() throws Exception {
        byte[] body = mp4(32);
        transport.reply("https://cdn.example.test/video.mp4", 200, null, "video/mp4", body);

        Path downloadedPath;
        try (ReferenceVideoDownloader.Downloaded downloaded = downloader().download("https://cdn.example.test/video.mp4")) {
            downloadedPath = downloaded.path();
            assertEquals(body.length, downloaded.size());
            assertEquals(sha256(body), downloaded.sha256());
            assertArrayEquals(body, Files.readAllBytes(downloadedPath));
        }
        assertFalse(Files.exists(downloadedPath), "关闭后临时文件必须删除");
    }

    @Test
    void rejectsOversizeBeforeReadingBody() {
        transport.reply("https://cdn.example.test/video.mp4", 200, null, "video/mp4", 65, mp4(8));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> downloader().download("https://cdn.example.test/video.mp4"));

        assertEquals("reference_download_too_large", error.getCode());
        assertEquals(0, transport.bodyReads);
    }

    @Test
    void rejectsPrivateRedirectBeforeSecondConnection() throws Exception {
        transport.reply("https://cdn.example.test/start", 302, "https://127.0.0.1/video.mp4", "video/mp4", new byte[0]);
        resolver.rebind("127.0.0.1", InetAddress.getLoopbackAddress());

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> downloader().download("https://cdn.example.test/start"));

        assertEquals("reference_download_blocked", error.getCode());
        assertEquals(1, transport.connections, "私网重定向不得产生第二次连接");
    }

    @Test
    void rejectsHttpsDowngrade() {
        transport.reply("https://cdn.example.test/start", 302, "http://cdn.example.test/video.mp4", "video/mp4", new byte[0]);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> downloader().download("https://cdn.example.test/start"));

        assertEquals("reference_download_scheme", error.getCode());
        assertEquals(1, transport.connections);
    }

    @Test
    void rejectsNonMp4PayloadEvenWhenContentTypeLooksValid() {
        transport.reply("https://cdn.example.test/video.mp4", 200, null, "video/mp4", "<html>login</html>".getBytes());

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> downloader().download("https://cdn.example.test/video.mp4"));

        assertEquals("reference_download_invalid_media", error.getCode());
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        body[0] = 0;
        body[1] = 0;
        body[2] = 0;
        body[3] = 24;
        body[4] = 'f';
        body[5] = 't';
        body[6] = 'y';
        body[7] = 'p';
        return body;
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static final class FakeTransport implements ReferenceVideoTransport {
        private final Map<String, Reply> replies = new LinkedHashMap<>();
        private int connections;
        private int bodyReads;

        void reply(String url, int status, String location, String contentType, byte[] body) {
            reply(url, status, location, contentType, body.length, body);
        }

        void reply(String url, int status, String location, String contentType, long contentLength, byte[] body) {
            replies.put(url, new Reply(status, location, contentType, contentLength, body));
        }

        @Override
        public Response open(Request request) {
            connections++;
            Reply reply = replies.get(request.url().toString());
            if (reply == null) throw new IllegalStateException("未登记的出网目标: " + request.url());
            return new Response(reply.status(), reply.location(), reply.contentType(), reply.contentLength(),
                new ByteArrayInputStream(reply.body()) {
                    @Override
                    public synchronized int read() {
                        bodyReads++;
                        return super.read();
                    }

                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        bodyReads++;
                        return super.read(b, off, len);
                    }
                });
        }
    }

    private record Reply(int status, String location, String contentType, long contentLength, byte[] body) { }

    private static final class FakeResolver implements NewsTransport.Resolver {
        private final Map<String, List<InetAddress>> answers = new LinkedHashMap<>();

        FakeResolver() {
            try {
                answers.put("cdn.example.test", List.of(InetAddress.getByName("203.0.113.10")));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void rebind(String host, InetAddress address) {
            answers.put(host, List.of(address));
        }

        @Override
        public List<InetAddress> resolve(String host) throws IOException {
            List<InetAddress> answer = answers.get(host);
            return answer == null ? List.of(InetAddress.getByName(host)) : answer;
        }
    }
}
