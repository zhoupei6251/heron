package org.dromara.heron.service.impl;

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
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 尝试目录内的产物定稿、校验与内部清理；不暴露 HTTP 路径。
 */
@Service
public class ProductionArtifactService {

    private static final Pattern SAFE_KIND = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,8}");
    private static final Pattern SAFE_MEDIA_TYPE = Pattern.compile("[a-z]+/[a-z0-9.+-]{1,64}");

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
        } catch (AtomicMoveNotSupportedException e) {
            throw new HeronBusinessException(409, "production_atomic_move_required", "不支持同卷原子定稿");
        } catch (IOException e) {
            throw new IllegalStateException("产物定稿失败", e);
        }
        return new ProductionArtifactVo(artifactId, kind, storageKey(target), size, sha256, mediaType);
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

    public void removeFile(ProductionArtifactVo artifact) {
        verify(artifact);
        try {
            Files.delete(resolveStorageKey(artifact.storageKey()));
        } catch (IOException e) {
            throw new IllegalStateException("删除产物失败", e);
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
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realDirectory(root))) {
                throw new HeronBusinessException(409, "production_file_missing", "产物路径越界");
            }
            return real;
        } catch (IOException e) {
            throw new HeronBusinessException(409, "production_file_missing", "产物文件不存在");
        }
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
