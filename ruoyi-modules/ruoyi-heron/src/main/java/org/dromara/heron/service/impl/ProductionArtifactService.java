package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.exception.S3StorageException;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.GetObjectResult;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
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
    private static final int CLEANUP_BATCH = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 本次尝试已定稿的存储键：任务没落库时按它回收，否则桶和磁盘都会留下无人引用的产物。 */
    private final Map<String, List<String>> finalized = new ConcurrentHashMap<>();
    private final Map<String, OssDTO> uploaded = new ConcurrentHashMap<>();
    private final HeronProductionProperties properties;
    private final OssService ossService;
    private final Clock clock;

    @Autowired
    public ProductionArtifactService(HeronProductionProperties properties, OssService ossService) {
        this(properties, ossService, Clock.systemUTC());
    }

    public ProductionArtifactService(HeronProductionProperties properties, OssService ossService, Clock clock) {
        this.properties = properties;
        this.ossService = ossService;
        this.clock = clock;
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
            ProductionArtifactVo artifact = new ProductionArtifactVo(artifactId, kind, storageKey(target), size, sha256, mediaType);
            record(artifact, run);
            uploadIfConfigured(run, target, mediaType, sha256, size);
            return artifact;
        } catch (AtomicMoveNotSupportedException e) {
            throw new HeronBusinessException(409, "production_atomic_move_required", "不支持同卷原子定稿");
        } catch (IOException e) {
            throw new IllegalStateException("产物定稿失败", e);
        }
    }

    private ProductionArtifactVo record(ProductionArtifactVo artifact, ProductionRunVo run) {
        finalized.computeIfAbsent(attemptKey(run),
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

    /** 本地绝对路径：浏览器自动化只能传本地文件；对象存储模式下没有本地文件，返回 null。 */
    public Path localPath(ProductionArtifactVo artifact) {
        return oss() == null ? resolveStorageKey(artifact.storageKey()) : null;
    }

    public void removeFile(ProductionArtifactVo artifact) {
        verify(artifact);
        OssClient oss = oss();
        if (oss != null) {
            oss.delete(artifact.storageKey());
            deleteLocalIfExists(artifact.storageKey());
            removeRegistration(artifact);
            return;
        }
        try {
            Files.delete(resolveStorageKey(artifact.storageKey()));
        } catch (IOException e) {
            throw new IllegalStateException("删除产物失败", e);
        }
    }

    /** 清理可续跑：桶对象已消失时仍补删元数据与本地副本，本地缺失则视为已清理。 */
    public void cleanupFile(ProductionArtifactVo artifact) {
        if (exists(artifact)) {
            removeFile(artifact);
        } else {
            deleteLocalIfExists(artifact.storageKey());
            removeRegistration(artifact);
        }
    }

    /** 按登记时间清理 15 天前的孤儿元数据；仍存在的对象必须走带发布保护的版本清理。 */
    public int cleanupExpiredUploads(Duration retention) {
        String configKey = properties.getOssConfigKey();
        if (configKey == null || configKey.isBlank()) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(retention);
        Set<String> scanned = new HashSet<>();
        int cleaned = 0;
        int limit = CLEANUP_BATCH;
        // shortcut: sys_oss 查询只有前缀 limit，没有 ossId 游标；递增批次直到没有新键，数据量上来后再换游标分页。
        while (true) {
            List<String> keys = ossService.listUploadedKeysBefore(configKey, "heron", cutoff, limit);
            List<String> fresh = keys.stream().filter(scanned::add).toList();
            for (String key : fresh) {
                try {
                    cleanupUploaded(configKey, key);
                    cleaned++;
                } catch (RuntimeException e) {
                    log.warn("过期云端产物清理未成功: key={}, cause={}", key, e.getClass().getSimpleName());
                }
            }
            if (keys.size() < limit) {
                break;
            }
            if (limit >= 100_000) {
                log.warn("云端孤儿元数据超过单轮扫描上限，剩余记录留到下一轮");
                break;
            }
            limit = Math.min(limit * 2, 100_000);
        }
        return cleaned;
    }

    private void cleanupUploaded(String configKey, String storageKey) {
        storageCandidate(storageKey);
        if (!storageKey.startsWith("videos/") && !storageKey.startsWith("protos/")) {
            throw new HeronBusinessException(409, "production_invalid_artifact", "云端产物路径不合法");
        }
        OssClient oss = oss();
        if (oss != null && head(oss, storageKey) != null) {
            throw new HeronBusinessException(409, "production_artifact_still_present",
                "云端对象仍存在，必须由带发布保护的版本清理删除");
        }
        ossService.unregisterUploaded(configKey, storageKey);
    }

    private void removeRegistration(ProductionArtifactVo artifact) {
        String configKey = properties.getOssConfigKey();
        if (configKey == null || configKey.isBlank()) {
            return;
        }
        storageCandidate(artifact.storageKey());
        ossService.unregisterUploaded(configKey, artifact.storageKey());
    }

    /** 配置了 oss-config-key 才走对象存储；留空即本地产物盘，测试与未接桶的部署行为完全不变。 */
    private OssClient oss() {
        String configKey = properties.getOssConfigKey();
        return configKey == null || configKey.isBlank() ? null : OssFactory.instance(configKey);
    }

    /** 定稿即上传；本地副本保留到版本清理（15 天），供发布与抽帧直接用，不即时删。 */
    private void uploadIfConfigured(ProductionRunVo run, Path target, String mediaType, String sha256, long size) {
        OssClient oss = oss();
        if (oss == null) {
            return;
        }
        try {
            PutObjectResult result = oss.upload(storageKey(target), target, Options.create()
                .setContentType(mediaType).setMetadata(Map.of("sha256", sha256)));
            OssDTO row = new OssDTO();
            row.setService(properties.getOssConfigKey());
            row.setFileName(result.key());
            row.setOriginalName(target.getFileName().toString());
            row.setFileSuffix("." + extensionOf(target));
            row.setUrl(result.url());
            row.setExt1(JSON.writeValueAsString(Map.of("bizType", run.bizType().name(), "fileSize", size,
                "contentType", mediaType, "source", "heron", "refId", Long.toString(run.bizId()),
                "refType", run.bizType().name(), "isTemp", false)));
            uploaded.put(uploadKey(run, result.key()), row);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "production_object_store_failed",
                "产物上传对象存储失败: " + e.getClass().getSimpleName());
        }
    }

    /** 必须与版本清单同一事务登记；失败尝试由 releaseAttempt 回收，不产生 sys_oss 行。 */
    public void register(ProductionRunVo run, List<ProductionArtifactVo> artifacts) {
        if (oss() == null) {
            return;
        }
        for (ProductionArtifactVo artifact : artifacts) {
            OssDTO row = uploaded.get(uploadKey(run, artifact.storageKey()));
            if (row == null) {
                throw new HeronBusinessException(503, "production_object_store_failed", "产物对象存储登记信息缺失");
            }
            ossService.registerUploaded(row);
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
        releaseAttempt(run, landed, () -> true);
    }

    /** 删除前再次确认调用方仍拥有该尝试且对应版本尚未成功落库；状态不明时保留字节。 */
    public void releaseAttempt(ProductionRunVo run, boolean landed, BooleanSupplier stillUncommitted) {
        if (!landed && !stillUncommitted.getAsBoolean()) {
            return;
        }
        List<String> keys = finalized.remove(attemptKey(run));
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<OssDTO> pending = keys.stream().map(key -> uploaded.remove(uploadKey(run, key))).toList();
        if (landed) {
            return;
        }
        OssClient oss = oss();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            OssDTO row = pending.get(i);
            boolean deleted = false;
            if (oss != null && row != null) {
                markTemporary(row);
                try {
                    ossService.registerUploaded(row);
                } catch (RuntimeException e) {
                    log.warn("失败尝试待清理元数据登记未成功: key={}, cause={}",
                        key, e.getClass().getSimpleName());
                }
                try {
                    oss.delete(key);
                    deleted = true;
                } catch (RuntimeException e) {
                    log.warn("回收失败尝试桶对象未成功: key={}, cause={}",
                        key, e.getClass().getSimpleName());
                }
                if (deleted) {
                    try {
                        ossService.unregisterUploaded(properties.getOssConfigKey(), key);
                    } catch (RuntimeException e) {
                        log.warn("失败尝试元数据注销未成功: key={}, cause={}",
                            key, e.getClass().getSimpleName());
                    }
                }
            } else if (oss != null) {
                try {
                    oss.delete(key);
                } catch (RuntimeException e) {
                    log.warn("回收失败尝试桶对象未成功: key={}, cause={}",
                        key, e.getClass().getSimpleName());
                }
            }
            try {
                deleteLocalIfExists(key);
            } catch (RuntimeException e) {
                log.warn("回收失败尝试本地产物未成功: key={}, cause={}",
                    key, e.getClass().getSimpleName());
            }
        }
    }

    private static void markTemporary(OssDTO row) {
        try {
            Map<String, Object> ext = JSON.readValue(row.getExt1(), Map.class);
            ext.put("isTemp", true);
            row.setExt1(JSON.writeValueAsString(ext));
        } catch (RuntimeException e) {
            log.warn("失败尝试扩展标记失败: key={}, cause={}", row.getFileName(), e.getClass().getSimpleName());
        }
    }

    private static String uploadKey(ProductionRunVo run, String storageKey) {
        return attemptKey(run) + ":" + storageKey;
    }

    private static String attemptKey(ProductionRunVo run) {
        return run.taskId() + ":" + run.attemptNo() + ":" + run.runToken();
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

    private void deleteLocalIfExists(String storageKey) {
        Path candidate = storageCandidate(storageKey);
        try {
            Path parent = candidate.getParent().toRealPath();
            if (!parent.startsWith(dataRoot().toRealPath())) {
                throw new HeronBusinessException(409, "production_file_missing", "产物路径越界");
            }
            Files.deleteIfExists(candidate);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            throw new IllegalStateException("删除本地产物失败", e);
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
