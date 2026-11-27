package org.dromara.heron.service.impl.shared.publish;

import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.service.IPublishAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** YouTube Data API v3 发布出口；独立开关、OAuth 凭据与上传文件缺一不可。 */
@Service
@ConditionalOnProperty(prefix = "heron.news", name = "publish-adapter", havingValue = YouTubePublishAdapter.SOURCE)
@Slf4j
public class YouTubePublishAdapter implements IPublishAdapter {
    public static final String SOURCE = "youtube";
    static final String URL_PREFIX = "https://www.youtube.com/watch?v=";
    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final URI UPLOAD_URI = URI.create("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status");
    private static final Set<String> PRIVACY_STATUSES = Set.of("private", "unlisted", "public");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronNewsProperties properties;
    private final HttpClient client;
    private final URI tokenUri;
    private final URI uploadUri;
    private final boolean allowLocalUploadLocation;

    @Autowired
    public YouTubePublishAdapter(HeronNewsProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build(), TOKEN_URI, UPLOAD_URI, false);
    }

    /** 仅供本地契约测试替换官方端点，生产配置不能改写端点。 */
    public YouTubePublishAdapter(HeronNewsProperties properties, HttpClient client, URI tokenUri, URI uploadUri) {
        this(properties, client, tokenUri, uploadUri, true);
    }

    public YouTubePublishAdapter(HeronNewsProperties properties, HttpClient client, URI tokenUri, URI uploadUri,
                                  boolean allowLocalUploadLocation) {
        this.properties = properties;
        this.client = client;
        this.tokenUri = tokenUri;
        this.uploadUri = uploadUri;
        this.allowLocalUploadLocation = allowLocalUploadLocation;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String account() {
        String account = properties.getYoutubeAccount() == null ? "" : properties.getYoutubeAccount().strip();
        return account.isEmpty() ? "youtube" : account;
    }

    @Override
    public void validate() {
        if (!properties.isYoutubeEnabled()) {
            throw new HeronBusinessException(503, "youtube_disabled", "YouTube 发布出口未开启，请显式配置 heron.news.youtube-enabled=true");
        }
        requireCredential("youtube-client-id", properties.getYoutubeClientId());
        requireCredential("youtube-client-secret", properties.getYoutubeClientSecret());
        requireCredential("youtube-refresh-token", properties.getYoutubeRefreshToken());
        if (!PRIVACY_STATUSES.contains(properties.getYoutubePrivacyStatus())) {
            throw new HeronBusinessException(503, "youtube_privacy_invalid", "heron.news.youtube-privacy-status 必须是 private、unlisted 或 public");
        }
    }

    private static void requireCredential(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new HeronBusinessException(503, "youtube_credentials_missing", "YouTube 凭据缺失：heron.news." + key);
        }
    }

    @Override
    public PublishReceipt publish(PublishRequest request) {
        validate();
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new HeronBusinessException(409, "publish_title_missing", "稿件没有标题，不能发布");
        }
        String title = request.title().strip();
        if (title.length() > 100) {
            throw new HeronBusinessException(409, "publish_title_invalid", "YouTube 标题不能超过 100 个字符");
        }
        Path file = request.videoFile();
        if (file == null || !Files.isRegularFile(file)) {
            throw new HeronBusinessException(409, "publish_file_missing", "成片没有本地文件，无法上传 YouTube");
        }
        Path uploadFile;
        long length;
        try {
            uploadFile = Files.createTempFile("heron-youtube-", ".mp4");
            Files.copy(file, uploadFile, StandardCopyOption.REPLACE_EXISTING);
            length = Files.size(uploadFile);
        } catch (java.io.IOException e) {
            throw new HeronBusinessException(503, "publish_file_unreadable", "无法读取成片文件", e);
        }
        if (length == 0) {
            deleteQuietly(uploadFile);
            throw new HeronBusinessException(409, "publish_file_empty", "成片文件为空，不能上传 YouTube");
        }
        try {
            if (!sha256(uploadFile).equalsIgnoreCase(request.videoSha256())) {
                throw new HeronBusinessException(409, "publish_hash", "上传文件 SHA-256 与审核指纹不一致");
            }
            String accessToken = accessToken();
            String uploadUrl = startUpload(accessToken, title, length, request.description());
            JsonNode video = upload(accessToken, uploadUrl, uploadFile, length);
            String id = video.path("id").asString("");
            if (!id.matches("[A-Za-z0-9_-]{11}")) {
                throw new HeronBusinessException(502, "youtube_receipt_invalid", "YouTube 上传响应缺少有效视频 ID");
            }
            String receiptAccount = request.account() == null ? account() : request.account().strip();
            return new PublishReceipt(URL_PREFIX + id, receiptAccount, request.requestedAt().truncatedTo(ChronoUnit.MILLIS), SOURCE);
        } finally {
            deleteQuietly(uploadFile);
        }
    }

    private String accessToken() {
        String form = form(List.of("grant_type", "refresh_token", "client_id", properties.getYoutubeClientId(),
            "client_secret", properties.getYoutubeClientSecret(), "refresh_token", properties.getYoutubeRefreshToken()));
        HttpRequest request = HttpRequest.newBuilder(tokenUri).timeout(timeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        JsonNode response = send(request, "youtube_token_failed", "获取 YouTube 访问令牌失败");
        String token = response.path("access_token").asString("");
        if (token.isBlank()) {
            throw new HeronBusinessException(502, "youtube_token_invalid", "YouTube 令牌响应缺少 access_token");
        }
        return token;
    }

    private String startUpload(String token, String title, long length, String description) {
        String body = JSON.writeValueAsString(java.util.Map.of(
            "snippet", java.util.Map.of("title", title, "description", description == null ? "" : description, "categoryId", "27"),
            "status", java.util.Map.of("privacyStatus", properties.getYoutubePrivacyStatus(),
                "selfDeclaredMadeForKids", false)));
        HttpRequest request = HttpRequest.newBuilder(uploadUri).timeout(timeout())
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", Long.toString(length))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> response = sendVoid(request, "youtube_upload_start_failed", "创建 YouTube 上传会话失败");
        String location = response.headers().firstValue("Location").orElse("");
        if (!uploadLocation(location)) {
            throw new HeronBusinessException(502, "youtube_upload_location_invalid", "YouTube 上传会话地址不合法");
        }
        return location;
    }

    private JsonNode upload(String token, String location, Path file, long length) {
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.ofFile(file);
        } catch (java.io.IOException e) {
            throw new HeronBusinessException(503, "publish_file_unreadable", "无法读取成片文件");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(location)).timeout(timeout())
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "video/mp4")
            .header("Content-Range", "bytes 0-" + (length - 1) + "/" + length)
            .PUT(body).build();
        return send(request, "youtube_upload_failed", "上传 YouTube 视频失败");
    }

    private HttpResponse<Void> sendVoid(HttpRequest request, String code, String message) {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 第三方错误体可能回显令牌或请求内容，只保留阶段与状态码。
                throw new HeronBusinessException(502, code, message + "（HTTP " + response.statusCode() + "）");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "youtube_interrupted", message + "：请求被中断");
        } catch (java.io.IOException e) {
            throw new HeronBusinessException(502, code, message + "：网络请求失败");
        }
    }

    private JsonNode send(HttpRequest request, String code, String message) {
        HttpResponse<String> response = sendString(request, code, message);
        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException e) {
            throw new HeronBusinessException(502, code + "_invalid", message + "：响应不是合法 JSON");
        }
    }

    private HttpResponse<String> sendString(HttpRequest request, String code, String message) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 第三方错误体可能回显令牌或请求内容，只保留阶段与状态码。
                throw new HeronBusinessException(502, code, message + "（HTTP " + response.statusCode() + "）");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "youtube_interrupted", message + "：请求被中断");
        } catch (java.io.IOException e) {
            throw new HeronBusinessException(502, code, message + "：网络请求失败");
        }
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, properties.getPublishTimeoutSeconds()));
    }

    private boolean uploadLocation(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            boolean testEndpoint = allowLocalUploadLocation && host != null
                && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
            boolean secureOfficial = "https".equals(uri.getScheme()) && uri.getPort() == -1 && host != null
                && (host.equals("googleapis.com") || host.endsWith(".googleapis.com"));
            boolean localTest = "http".equals(uri.getScheme()) && testEndpoint;
            return uri.getUserInfo() == null && uri.getFragment() == null && (secureOfficial || localTest);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String sha256(Path file) {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new HeronBusinessException(503, "publish_file_unreadable", "无法计算成片 SHA-256", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            log.warn("YouTube 上传临时文件清理失败: {}", file, e);
        }
    }

    /** true 表示 value 是 {@code URL_PREFIX} 开头且后接 11 位视频 ID 的合法 YouTube 链接。 */
    public static boolean youtubeUrl(String value) {
        if (value == null || !value.startsWith(URL_PREFIX)) {
            return false;
        }
        return value.substring(URL_PREFIX.length()).matches("[A-Za-z0-9_-]{11}");
    }

    private static String form(List<String> values) {
        return java.util.stream.IntStream.range(0, values.size() / 2)
            .mapToObj(i -> encode(values.get(i * 2)) + "=" + encode(values.get(i * 2 + 1)))
            .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
