package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 受控下载参考视频：逐跳校验、流式限流、MP4 魔数校验，产物先落临时文件。 */
@Component
public final class ReferenceVideoDownloader implements AutoCloseable {

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final List<String> ALLOWED_TYPES = List.of("video/mp4", "application/octet-stream");

    private final HeronNewsProperties properties;
    private final ReferenceVideoTransport transport;
    private final NewsTransport.Resolver resolver;
    private final Path tempDir;

    /**
     * 两个构造器并存，Spring 选不出用哪个：显式标注生产用的这个，另一个留给测试注入替身。
     */
    @Autowired
    public ReferenceVideoDownloader(HeronNewsProperties properties) {
        this(properties, new ApacheReferenceVideoTransport(properties), ReferenceVideoDownloader::resolve,
            Path.of(System.getProperty("java.io.tmpdir")));
    }

    ReferenceVideoDownloader(HeronNewsProperties properties, ReferenceVideoTransport transport,
                             NewsTransport.Resolver resolver, Path tempDir) {
        this.properties = properties;
        this.transport = transport;
        this.resolver = resolver;
        this.tempDir = tempDir;
    }

    public Downloaded download(String url) {
        return download(url, properties.getReferenceMaxBytes());
    }

    public Downloaded download(String url, long maxBytes) {
        if (maxBytes <= 0) {
            throw new HeronBusinessException(400, "reference_download_invalid_limit", "视频体积上限必须大于 0");
        }
        URI current = https(NewsUrlPolicy.normalize(url));
        int followed = 0;
        while (true) {
            String host = NewsUrlPolicy.hostOf(current);
            List<InetAddress> addresses;
            try {
                addresses = NewsAddressPolicy.requirePublic(resolver.resolve(host), host);
            } catch (HeronBusinessException e) {
                throw new HeronBusinessException(400, "reference_download_blocked", e.getMessage());
            } catch (IOException e) {
                throw new HeronBusinessException(502, "reference_download_unreachable", "视频域名无法解析: " + host);
            }
            try (ReferenceVideoTransport.Response response = transport.open(
                new ReferenceVideoTransport.Request(current, addresses.get(0)))) {
                int status = response.status();
                if (status >= 300 && status < 400) {
                    if (followed >= Math.max(0, properties.getReferenceMaxRedirects())) {
                        throw new HeronBusinessException(502, "reference_download_redirect_limit", "视频重定向次数超过上限");
                    }
                    if (response.location() == null || response.location().isBlank()) {
                        throw new HeronBusinessException(502, "reference_download_status", "视频重定向缺少目标地址");
                    }
                    current = https(NewsUrlPolicy.normalize(current.resolve(response.location()).toString()));
                    followed++;
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new HeronBusinessException(502, "reference_download_auth", "视频来源要求登录或拒绝访问: " + status);
                }
                if (status == 429) {
                    throw new HeronBusinessException(502, "reference_download_rate_limited", "视频来源限流: " + status);
                }
                if (status != 200) {
                    throw new HeronBusinessException(502, "reference_download_status", "视频来源返回异常状态: " + status);
                }
                return save(response, maxBytes);
            } catch (HeronBusinessException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new HeronBusinessException(502, "reference_download_unreachable", "视频下载失败: " + e.getMessage());
            }
        }
    }

    private Downloaded save(ReferenceVideoTransport.Response response, long maxBytes) {
        String contentType = response.contentType() == null ? "" : response.contentType().split(";", 2)[0]
            .trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new HeronBusinessException(502, "reference_download_invalid_media", "视频来源返回了不支持的类型: " + contentType);
        }
        if (response.contentLength() > maxBytes) {
            throw new HeronBusinessException(413, "reference_download_too_large", "视频文件超过单文件上限");
        }
        if (response.body() == null) {
            throw new HeronBusinessException(502, "reference_download_invalid_media", "视频来源没有返回文件内容");
        }
        Path path = createTempFile();
        boolean landed = false;
        try (InputStream input = response.body()) {
            MessageDigest digest = sha256();
            long size = 0;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (var output = Files.newOutputStream(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    size += read;
                    if (size > maxBytes) {
                        throw new HeronBusinessException(413, "reference_download_too_large", "视频文件超过单文件上限");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            if (size == 0 || !isMp4(path)) {
                throw new HeronBusinessException(502, "reference_download_invalid_media", "下载内容不是有效 MP4");
            }
            landed = true;
            return new Downloaded(path, size, HexFormat.of().formatHex(digest.digest()), contentType);
        } catch (HeronBusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new HeronBusinessException(502, "reference_download_unreachable", "视频文件写入失败: " + e.getMessage());
        } finally {
            if (!landed) {
                deleteQuietly(path);
            }
        }
    }

    private Path createTempFile() {
        try {
            return Files.createTempFile(tempDir, "heron-reference-", ".mp4");
        } catch (IOException e) {
            throw new HeronBusinessException(503, "reference_download_unavailable", "无法创建视频临时文件: " + e.getMessage());
        }
    }

    private static boolean isMp4(Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(8);
            while (header.hasRemaining() && channel.read(header) >= 0) {
                // 继续读到 8 字节或 EOF
            }
            byte[] bytes = header.array();
            return bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static URI https(URI uri) {
        if (!"https".equals(uri.getScheme())) {
            throw new HeronBusinessException(400, "reference_download_scheme", "参考视频只允许 HTTPS 下载");
        }
        return uri;
    }

    private static List<InetAddress> resolve(String host) throws IOException {
        return List.of(InetAddress.getAllByName(host));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("参考视频临时文件删除失败: " + path, e);
        }
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (Exception e) {
            throw new UncheckedIOException("参考视频下载客户端关闭失败", new IOException(e));
        }
    }

    public record Downloaded(Path path, long size, String sha256, String contentType) implements AutoCloseable {
        @Override
        public void close() {
            deleteQuietly(path);
        }
    }
}
