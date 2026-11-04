package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.exception.S3StorageException;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.GetObjectResult;
import org.dromara.common.oss.model.Options;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 尝试目录内的产物定稿、校验与内部清理；不暴露 HTTP 路径。
 */
@Slf4j
@Service
public class ProductionArtifactService {

    private static final Pattern SAFE_KIND = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,8}");
    private static final Pattern SAFE_MEDIA_TYPE = Pattern.compile("[a-z]+/[a-z0-9.+-]{1,64}");

    /** 本次尝试已定稿的存储键：任务没落库时按它回收，否则桶和磁盘都会留下无人引用的产物。 */
    private final Map<String, List<String>> finalized = new ConcurrentHashMap<>();
    private final HeronProductionProperties properties;

    public ProductionArtifactService(HeronProductionProperties properties) {
        this.properties = properties;
    }

    public Path prepareWorkDirectory(ProductionRunVo run) {
        Path directory = workDirectory(run);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("创建尝试目录失败", e);
        }
        return directory;
    }

    public ProductionArtifactVo finalizeFile(ProductionRunVo run, String kind, Path file, String mediaType) {
        return finalizeFile(run, kind, file, mediaType, null);
    }

    /**
     * 原型版本号来自快照，必须由调用方显式给出；新闻线以版本记录 ID 定稿。
     */
    public ProductionArtifactVo finalizeFile(ProductionRunVo run, String kind, Path file, String mediaType,
                                             Long versionNo) {
        if (kind == null || !SAFE_KIND.matcher(kind).matches()
            || mediaType == null || !SAFE_MEDIA_TYPE.matcher(mediaType).matches()) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物类型或媒体类型不合法");
        }
        Path source = realFile(file);
        Path work = realDirectory(prepareWorkDirectory(run));
        if (!source.startsWith(work)) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物不在本次尝试目录内");
        }
        String extension = extensionOf(source);
        long size = sizeOf(source);
        long limit = run.bizType() == ProductionBizType.prototype
            ? properties.getPrototypeMaxBytes() : properties.getNewsMaxBytes();
        if (size <= 0) {
            throw new HeronBusinessException(409, "production_file_invalid", "空产物不可定稿");
        }
        if (size > limit) {
            throw new HeronBusinessException(409, "production_file_invalid", "产物超过单文件上限");
        }
        if (run.bizType() == ProductionBizType.prototype && (versionNo == null || versionNo < 1)) {
            throw new HeronBusinessException(400, "production_invalid_version", "缺少原型快照版本号");
        }

        String artifactId = UUID.randomUUID().toString();
        String sha256 = sha256(source);
        Path targetDirectory = finalDirectory(run, versionNo);
        Path target = targetDirectory.resolve(artifactId + "." + extension);
        try {
            Files.createDirectories(targetDirectory);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new HeronBusinessException(409, "production_file_invalid", "产物已存在");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            uploadIfConfigured(target, mediaType, sha256);
        } catch (AtomicMoveNotSupportedException e) {
            throw new HeronBusinessException(409, "production_atomic_move_required", "不支持同卷原子定稿");
        } catch (IOException e) {
            throw new IllegalStateException("产物定稿失败", e);
        }
        return record(new ProductionArtifactVo(artifactId, kind, storageKey(target), size, sha256, mediaType), run);
    }

    private ProductionArtifactVo record(ProductionArtifactVo artifact, ProductionRunVo run) {
        finalized.computeIfAbsent(run.taskId() + ":" + run.attemptNo(),
            key -> Collections.synchronizedList(new ArrayList<>())).add(artifact.storageKey());
        return artifact;
    }

    /**
     * 失败诊断证据放在尝试目录的 .keep 下，不进入成功产物清单，清理 dry-run 也会跳过。
     */
    public String keepEvidence(ProductionRunVo run, Path file) {
        Path source = realFile(file);
        Path work = realDirectory(prepareWorkDirectory(run));
        if (!source.startsWith(work)) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "证据不在本次尝试目录内");
        }
        String extension = extensionOf(source);
        long size = sizeOf(source);
        if (size <= 0 || size > properties.getNewsMaxBytes()) {
            throw new HeronBusinessException(409, "production_file_invalid", "证据大小不合法");
        }
        Path directory = work.resolve(".keep");
        Path target = directory.resolve(UUID.randomUUID() + "." + extension);
        try {
            Files.createDirectories(directory);
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new HeronBusinessException(409, "production_atomic_move_required", "不支持同卷原子保留");
        } catch (IOException e) {
            throw new IllegalStateException("保留失败证据失败", e);
        }
        return storageKey(target);
    }

    public void verify(ProductionArtifactVo artifact) {
        if (artifact == null) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物描述缺失");
        }
        OssClient oss = oss();
        if (oss != null) {
            GetObjectResult object = head(oss, artifact.storageKey());
            if (object == null) {
                throw new HeronBusinessException(409, "production_file_missing", "产物文件不存在");
            }
            if (object.size() != artifact.bytes()) {
                throw new HeronBusinessException(409, "production_file_corrupt", "产物长度不符");
            }
            if (object.metadata() == null || !artifact.sha256().equals(object.metadata().get("sha256"))) {
                throw new HeronBusinessException(409, "production_file_corrupt", "产物哈希不符");
            }
            return;
        }
        Path file = resolveStorageKey(artifact.storageKey());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new HeronBusinessException(409, "production_file_missing", "产物文件不存在");
        }
        if (sizeOf(file) != artifact.bytes()) {
            throw new HeronBusinessException(409, "production_file_corrupt", "产物长度不符");
        }
        if (!sha256(file).equals(artifact.sha256())) {
            throw new HeronBusinessException(409, "production_file_corrupt", "产物哈希不符");
        }
    }

    /**
     * 校验后交给 controller 的字节流。对象存储模式返回阻塞流，由调用方（NewsFileVo / ProtoFile）关闭。
     */
    public InputStream openVerified(ProductionArtifactVo artifact) {
        verify(artifact);
        OssClient oss = oss();
        if (oss != null) {
            String bucket = oss.config().bucket().orElseThrow(() -> new HeronBusinessException(
                503, "production_object_store_failed", "对象存储未配置桶名"));
            return oss.doCustomDownload(builder -> builder.bucket(bucket).key(artifact.storageKey()),
                AsyncResponseTransformer.toBlockingInputStream(), null);
        }
        try {
            return Files.newInputStream(resolveStorageKey(artifact.storageKey()),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new HeronBusinessException(409, "production_file_unreadable", "产物不可读");
        }
    }

    /**
     * 产物字节当前在不在：本地看文件，对象存储看元数据请求。清理要能续跑，不能假设本地路径还存在。
     */
    public boolean exists(ProductionArtifactVo artifact) {
        OssClient oss = oss();
        if (oss != null) {
            return head(oss, artifact.storageKey()) != null;
        }
        try {
            return Files.exists(storageCandidate(artifact.storageKey()), LinkOption.NOFOLLOW_LINKS);
        } catch (HeronBusinessException e) {
            return false;
        }
    }

    public void removeFile(ProductionArtifactVo artifact) {
        verify(artifact);
        OssClient oss = oss();
        if (oss != null) {
            oss.delete(artifact.storageKey());
            return;
        }
        try {
            Files.delete(resolveStorageKey(artifact.storageKey()));
        } catch (IOException e) {
            throw new IllegalStateException("删除产物失败", e);
        }
    }

    /** 配置了 oss-config-key 才走对象存储；留空即本地产物盘，测试与未接桶的部署行为完全不变。 */
    private OssClient oss() {
        String configKey = properties.getOssConfigKey();
        return configKey == null || configKey.isBlank() ? null : OssFactory.instance(configKey);
    }

    /** 定稿即上传、上传成功才删本地：磁盘只当工作区，桶才是产物的长期落点。 */
    private void uploadIfConfigured(Path target, String mediaType, String sha256) {
        OssClient oss = oss();
        if (oss == null) {
            return;
        }
        try {
            oss.upload(storageKey(target), target, Options.create()
                .setContentType(mediaType).setMetadata(Map.of("sha256", sha256)));
            Files.delete(target);
        } catch (IOException | RuntimeException e) {
            throw new HeronBusinessException(503, "production_object_store_failed",
                "产物上传对象存储失败: " + e.getClass().getSimpleName());
        }
    }

    /** 只取元数据：拿到响应头就结束，S3 不会把整段成片拉回本机。 */
    private static GetObjectResult head(OssClient oss, String key) {
        try {
            return oss.download(key, (meta, content) -> meta);
        } catch (S3StorageException e) {
            if (e.getCause() instanceof NoSuchKeyException) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 失败证据按 taskId+artifactId 重新定位，不信任客户端路径；校验与读取共用同一打开句柄。
     */
    public EvidenceStream openEvidence(long taskId, ProductionBizType type, String artifactId,
                                       long bytes, String sha256, String mediaType) {
        if (artifactId == null || !artifactId.matches("[0-9a-fA-F-]{36}")
            || mediaType == null || !SAFE_MEDIA_TYPE.matcher(mediaType).matches()
            || bytes <= 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new HeronBusinessException(404, "production_evidence_missing", "证据不存在");
        }
        Path realRoot;
        try {
            realRoot = dataRoot().resolve("work").resolve(type.name())
                .resolve(Long.toString(taskId)).toRealPath();
        } catch (IOException e) {
            throw new HeronBusinessException(410, "production_evidence_gone", "证据已清理");
        }
        Path candidate = findEvidence(realRoot, artifactId);
        if (candidate == null) {
            throw new HeronBusinessException(410, "production_evidence_gone", "证据已清理");
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new HeronBusinessException(410, "production_evidence_gone", "证据已清理");
            }
            FileChannel channel = FileChannel.open(real, StandardOpenOption.READ);
            try {
                if (channel.size() != bytes) {
                    throw new HeronBusinessException(409, "production_file_corrupt", "证据长度不符");
                }
                if (!sha256(channel).equals(sha256)) {
                    throw new HeronBusinessException(409, "production_file_corrupt", "证据哈希不符");
                }
                channel.position(0);
                return new EvidenceStream(real.getFileName().toString(), mediaType, bytes,
                    Channels.newInputStream(channel));
            } catch (RuntimeException | IOException e) {
                channel.close();
                throw e;
            }
        } catch (IOException e) {
            throw new HeronBusinessException(410, "production_evidence_gone", "证据已清理");
        }
    }

    public record EvidenceStream(String fileName, String mediaType, long bytes, InputStream content) {
    }

    private Path findEvidence(Path taskRoot, String artifactId) {
        try (var paths = Files.walk(taskRoot, 4)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getParent() != null
                    && ".keep".equals(path.getParent().getFileName().toString()))
                .filter(path -> path.getFileName().toString().startsWith(artifactId + "."))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            throw new HeronBusinessException(410, "production_evidence_gone", "证据已清理");
        }
    }

    private Path workDirectory(ProductionRunVo run) {
        return dataRoot()
            .resolve("work")
            .resolve(run.bizType().name())
            .resolve(Long.toString(run.taskId()))
            .resolve("a" + run.attemptNo() + "-" + UUID.fromString(run.runToken()));
    }

    /**
     * 尝试收尾：落库成功只清记账；没落库就把本次已定稿的字节收走，不留无人引用的产物。
     * 失败证据在 work/.keep 下，不在这份清单里，不会被误删。
     */
    public void releaseAttempt(ProductionRunVo run, boolean landed) {
        List<String> keys = finalized.remove(run.taskId() + ":" + run.attemptNo());
        if (landed || keys == null || keys.isEmpty()) {
            return;
        }
        OssClient oss = oss();
        for (String key : keys) {
            try {
                if (oss != null) {
                    oss.delete(key);
                } else {
                    Files.deleteIfExists(resolveStorageKey(key));
                }
            } catch (RuntimeException | IOException e) {
                log.warn("回收失败尝试产物未成功: key={}, cause={}", key, e.getClass().getSimpleName());
            }
        }
    }

    private Path finalDirectory(ProductionRunVo run, Long versionNo) {
        boolean news = run.bizType() == ProductionBizType.news_video;
        String lane = news ? "videos" : "protos";
        String versionKey = "v" + (news ? run.versionId() : versionNo);
        return dataRoot()
            .resolve(lane)
            .resolve(Long.toString(run.bizId()))
            .resolve(versionKey)
            .resolve("a" + run.attemptNo() + "-" + UUID.fromString(run.runToken()));
    }

    private String storageKey(Path target) {
        Path root = dataRoot();
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物路径越界");
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    private Path resolveStorageKey(String storageKey) {
        Path candidate = storageCandidate(storageKey);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realDirectory(dataRoot()))) {
                throw new HeronBusinessException(409, "production_file_missing", "产物路径越界");
            }
            return real;
        } catch (IOException e) {
            throw new HeronBusinessException(409, "production_file_missing", "产物文件不存在");
        }
    }

    /** 只做形状与越界检查，不要求文件存在：存在性与合法性必须分开判，否则符号链接会被误报成「已清理」。 */
    private Path storageCandidate(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.indexOf('\\') >= 0) {
            throw new HeronBusinessException(409, "production_file_missing", "产物路径不合法");
        }
        Path relative = Path.of(storageKey);
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
            throw new HeronBusinessException(409, "production_file_missing", "产物路径不合法");
        }
        Path root = dataRoot();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new HeronBusinessException(409, "production_file_missing", "产物路径越界");
        }
        return candidate;
    }

    private Path realFile(Path file) {
        if (file == null) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物文件缺失");
        }
        try {
            Path real = file.toRealPath();
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new HeronBusinessException(400, "production_invalid_artifact", "产物不是普通文件");
            }
            return real;
        } catch (IOException e) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物文件不可读");
        }
    }

    private Path realDirectory(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("尝试目录不可用", e);
        }
    }

    private Path dataRoot() {
        return properties.getDataRoot().toAbsolutePath().normalize();
    }

    private static String extensionOf(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SAFE_EXTENSION.matcher(extension).matches()) {
            throw new HeronBusinessException(400, "production_invalid_artifact", "产物扩展名不合法");
        }
        return extension;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new HeronBusinessException(409, "production_file_missing", "产物文件不可读");
        }
    }

    private static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new HeronBusinessException(409, "production_file_missing", "产物文件不可读");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(FileChannel channel) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
