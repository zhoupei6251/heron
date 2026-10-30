package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.*;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronProtoProjectMapper;
import org.dromara.heron.mapper.HeronProtoVersionMapper;
import org.dromara.heron.service.IProductionService;
import org.dromara.heron.service.IProtoProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 项目草稿 CAS、人工审核/验证/交付和受保护文件访问。 */
@Service
public class ProtoProjectServiceImpl implements IProtoProjectService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HeronProtoProjectMapper projectMapper;
    private final HeronProtoVersionMapper versionMapper;
    private final HeronProductionTaskMapper taskMapper;
    private final IProductionService production;
    private final HeronAccessService access;
    private final ProtoProductionDomain domain;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final Clock clock;
    private final String targetAxureVersion;

    @Autowired
    public ProtoProjectServiceImpl(HeronProtoProjectMapper projectMapper, HeronProtoVersionMapper versionMapper,
                                   HeronProductionTaskMapper taskMapper, IProductionService production,
                                   HeronAccessService access, ProtoProductionDomain domain,
                                   ProductionArtifactService artifacts, HeronProductionProperties properties,
                                   @Value("${heron.prototype.target-axure-version:}") String targetAxureVersion) {
        this(projectMapper, versionMapper, taskMapper, production, access, domain, artifacts, properties,
            Clock.systemUTC(), targetAxureVersion);
    }

    public ProtoProjectServiceImpl(HeronProtoProjectMapper projectMapper, HeronProtoVersionMapper versionMapper,
                                   HeronProductionTaskMapper taskMapper, IProductionService production,
                                   HeronAccessService access, ProtoProductionDomain domain,
                                   ProductionArtifactService artifacts, HeronProductionProperties properties,
                                   Clock clock, String targetAxureVersion) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.taskMapper = taskMapper;
        this.production = production;
        this.access = access;
        this.domain = domain;
        this.artifacts = artifacts;
        this.properties = properties;
        this.clock = clock;
        this.targetAxureVersion = targetAxureVersion == null ? "" : targetAxureVersion.strip();
    }

    @Override
    public ProtoProjectVo queryById(long projectId) {
        return MapstructUtils.convert(requireProject(projectId, "query", false), ProtoProjectVo.class);
    }

    @Override
    public PageResult<ProtoProjectVo> queryPageList(ProtoProjectQuery query, PageQuery pageQuery) {
        access.requirePermission("heron:proto:list");
        if (query == null || query.getArchived() == null || !Set.of("0", "1").contains(query.getArchived())) {
            invalid("归档筛选值不合法");
        }
        var wrapper = QueryBuilder.lambda(HeronProtoProject.class)
            .likeIfText(HeronProtoProject::getName, query.getName())
            .eq(HeronProtoProject::getArchived, query.getArchived())
            .eq(!access.hasAllScope(ProductionBizType.prototype), HeronProtoProject::getOwnerId, access.requireActor())
            .betweenParams(HeronProtoProject::getUpdateTime, query.getParams(), "beginUpdateTime", "endUpdateTime")
            .orderByDesc(HeronProtoProject::getUpdateTime, HeronProtoProject::getProjectId).build();
        Page<ProtoProjectVo> page = projectMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoProjectVo insertByBo(ProtoProjectBo bo) {
        access.requirePermission("heron:proto:add");
        var project = normalizedDraft(bo);
        project.setProjectId(IdUtil.getSnowflakeNextId());
        project.setOwnerId(access.requireActor());
        project.setRevision(1L);
        project.setNextVersion(1);
        project.setArchived("0");
        project.setCreateBy(access.requireActor());
        project.setUpdateBy(access.requireActor());
        project.setCreateTime(now());
        project.setUpdateTime(project.getCreateTime());
        if (projectMapper.insert(project) != 1) throw new IllegalStateException("项目创建失败");
        return MapstructUtils.convert(project, ProtoProjectVo.class);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoProjectVo updateByBo(long projectId, ProtoProjectBo bo) {
        var old = requireProject(projectId, "edit", true);
        var draft = normalizedDraft(bo);
        if (bo.getExpectedRevision() == null || !bo.getExpectedRevision().equals(old.getRevision())) {
            conflict("草稿已变更，请刷新后保存");
        }
        if (!"0".equals(old.getArchived())) conflict("归档项目不可编辑");
        if (old.getRevision() == Long.MAX_VALUE) conflict("草稿修订号已达上限");
        int changed = projectMapper.lambda()
            .set(HeronProtoProject::getName, draft.getName())
            .set(HeronProtoProject::getRequirement, draft.getRequirement())
            .set(HeronProtoProject::getCanvas, draft.getCanvas())
            .set(HeronProtoProject::getThemeKey, draft.getThemeKey())
            .set(HeronProtoProject::getRevision, old.getRevision() + 1)
            .set(HeronProtoProject::getUpdateBy, access.requireActor())
            .set(HeronProtoProject::getUpdateTime, now())
            .eq(HeronProtoProject::getProjectId, projectId)
            .eq(HeronProtoProject::getRevision, bo.getExpectedRevision()).updateCount();
        if (changed != 1) conflict("草稿已变更，请刷新后保存");
        return MapstructUtils.convert(projectMapper.selectById(projectId), ProtoProjectVo.class);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoProjectVo archive(long projectId) {
        var project = requireProject(projectId, "edit", true);
        if (project.getActiveTaskId() != null) conflict("项目有在途任务，不能归档");
        if (!"1".equals(project.getArchived())) {
            if (projectMapper.lambda().set(HeronProtoProject::getArchived, "1")
                .set(HeronProtoProject::getUpdateBy, access.requireActor())
                .set(HeronProtoProject::getUpdateTime, now())
                .eq(HeronProtoProject::getProjectId, projectId).isNull(HeronProtoProject::getActiveTaskId)
                .updateCount() != 1) conflict("项目状态已变化");
        }
        return MapstructUtils.convert(projectMapper.selectById(projectId), ProtoProjectVo.class);
    }

    @Override
    public ProductionReceiptVo generate(long projectId, ProtoGenerateBo bo) {
        requireProject(projectId, "generate", false);
        if (bo == null || bo.expectedRevision() < 1) invalid("生产请求必须指定已保存修订号");
        requireRequestKey(bo.requestKey());
        // 草稿、能力和容量检查留在 submit 幂等重放之后，不提前破坏旧请求重试。
        return production.submit(new ProductionSubmitBo(ProductionBizType.prototype, projectId,
            bo.requestKey(), bo.expectedRevision()));
    }

    @Override
    public PageResult<ProtoVersionVo> versions(long projectId, PageQuery pageQuery) {
        var project = requireProject(projectId, "query", false);
        var page = versionMapper.selectPage(pageQuery.<HeronProtoVersion>build(),
            QueryBuilder.lambda(HeronProtoVersion.class).eq(HeronProtoVersion::getProjectId, projectId)
                .eq(HeronProtoVersion::getOwnerId, project.getOwnerId())
                .orderByDesc(HeronProtoVersion::getVersionNo).build());
        return PageResult.build(page.getRecords().stream().map(this::toVersionVo).toList(), page.getTotal());
    }

    @Override
    public ProtoVersionVo version(long projectId, long versionId) {
        var project = requireProject(projectId, "query", false);
        var version = requireVersion(project, versionId, false);
        return toVersionVo(version);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoVersionVo review(long projectId, long versionId, ProtoReviewBo bo) {
        var locked = lockVersion(projectId, versionId, "review");
        if (bo == null || bo.decision() == null || !Set.of("approved", "rejected").contains(bo.decision())) {
            invalid("审核决定只能为 approved/rejected");
        }
        String reason = normalizeText(bo.reason(), "审核理由", "rejected".equals(bo.decision()) ? 1 : 0, 1000);
        var manifest = domain.verifyVersion(locked.version(), locked.task());
        requireHashes(manifest, bo.rpSha256(), bo.previewSha256());
        var version = locked.version();
        if (!"pending".equals(version.getReviewStatus())) {
            if (!Objects.equals(version.getReviewStatus(), bo.decision())
                || !Objects.equals(version.getReviewReason(), reason)
                || !Objects.equals(version.getReviewRpSha(), bo.rpSha256())
                || !Objects.equals(version.getReviewPreviewSha(), bo.previewSha256())) conflict("版本已有不同审核决定");
            return toVersionVo(version);
        }
        if (versionMapper.lambda().set(HeronProtoVersion::getReviewStatus, bo.decision())
            .set(HeronProtoVersion::getReviewBy, access.requireActor())
            .set(HeronProtoVersion::getReviewAt, now())
            .set(HeronProtoVersion::getReviewReason, reason)
            .set(HeronProtoVersion::getReviewRpSha, bo.rpSha256())
            .set(HeronProtoVersion::getReviewPreviewSha, bo.previewSha256())
            .set(HeronProtoVersion::getUpdateBy, access.requireActor())
            .set(HeronProtoVersion::getUpdateTime, now())
            .eq(HeronProtoVersion::getVersionId, versionId)
            .eq(HeronProtoVersion::getReviewStatus, "pending").updateCount() != 1) conflict("审核状态已变更");
        return toVersionVo(versionMapper.selectById(versionId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoNativeVerificationVo recordNativeVerification(long projectId, long versionId, ProtoNativeVerificationBo bo) {
        var locked = lockVersion(projectId, versionId, "verify");
        if (bo == null) invalid("缺少人工验证声明");
        requireRequestKey(bo.requestKey());
        requireHash(bo.rpSha256());
        String target = normalizeText(bo.targetVersion(), "目标 Axure 版本", 1, 160);
        String note = normalizeText(bo.note(), "验证备注", 0, 8000);
        var declaration = new ProtoNativeVerificationBo(bo.requestKey(), bo.rpSha256(), target,
            bo.opened(), bo.edited(), bo.saved(), bo.reopened(), bo.navigationChecked(), note);
        var manifest = domain.verifyVersion(locked.version(), locked.task());
        if (!manifest.rp().sha256().equals(bo.rpSha256())) conflict("原生文件已变化，请刷新后验证");
        var history = verificationEvents(locked.version());
        for (var event : history) {
            if (event.declaration().requestKey().equals(bo.requestKey())) {
                if (!event.declaration().equals(declaration)) conflict("同一验证请求键对应不同声明");
                return event.fact();
            }
        }
        if (history.size() >= 100) conflict("单版本最多保留 100 条人工验证记录");
        boolean passed = !targetAxureVersion.isBlank() && targetAxureVersion.equals(target)
            && bo.opened() && bo.edited() && bo.saved() && bo.reopened() && bo.navigationChecked();
        var fact = new ProtoNativeVerificationVo(UUID.randomUUID().toString(), bo.requestKey(), access.requireActor(),
            clock.instant().truncatedTo(ChronoUnit.MILLIS), bo.rpSha256(), target, passed, note);
        history.add(new NativeVerificationEvent(declaration, fact));
        String json = JSON.writeValueAsString(history);
        if (json.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) conflict("人工验证历史超过 256 KiB，不能继续追加");
        if (versionMapper.lambda().set(HeronProtoVersion::getNativeVerificationJson, json)
            .set(HeronProtoVersion::getUpdateBy, access.requireActor())
            .set(HeronProtoVersion::getUpdateTime, now())
            .eq(HeronProtoVersion::getVersionId, versionId).updateCount() != 1) conflict("版本已变更");
        return fact;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoDeliveryVo deliver(long projectId, long versionId, ProtoDeliverBo bo) {
        var locked = lockVersion(projectId, versionId, "deliver");
        if (bo == null) invalid("缺少交付确认");
        requireRequestKey(bo.requestKey());
        var version = locked.version();
        var manifest = domain.verifyVersion(version, locked.task());
        requireHashes(manifest, bo.rpSha256(), bo.previewSha256());
        requireApproved(version, manifest);
        if (version.getDeliveryAt() != null) {
            requireDeliveryHashes(version, manifest, bo.rpSha256());
            return deliveryVo(version);
        }
        ProtoNativeVerificationVo verified = null;
        for (var event : verificationEvents(version)) {
            var fact = event.fact();
            if (fact.passed() && fact.rpSha256().equals(bo.rpSha256()) && !targetAxureVersion.isBlank()
                && fact.targetVersion().equals(targetAxureVersion)) verified = fact;
        }
        LocalDateTime deliveredAt = now();
        long actor = access.requireActor();
        if (versionMapper.lambda().set(HeronProtoVersion::getDeliveryRequestKey, bo.requestKey())
            .set(HeronProtoVersion::getDeliveryBy, actor)
            .set(HeronProtoVersion::getDeliveryAt, deliveredAt)
            .set(HeronProtoVersion::getDeliveryRpSha, bo.rpSha256())
            .set(HeronProtoVersion::getDeliveryPreviewSha, bo.previewSha256())
            .set(HeronProtoVersion::getDeliveryNativeVerified, verified != null)
            .set(HeronProtoVersion::getDeliveryVerificationId, verified == null ? null : verified.verificationId())
            .set(HeronProtoVersion::getUpdateBy, actor)
            .set(HeronProtoVersion::getUpdateTime, deliveredAt)
            .eq(HeronProtoVersion::getVersionId, versionId).isNull(HeronProtoVersion::getDeliveryAt)
            .updateCount() != 1) conflict("交付事实已存在");
        return deliveryVo(versionMapper.selectById(versionId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoFile preview(long projectId, long versionId, String artifactId) {
        return openFile(projectId, versionId, artifactId, "reviewPreview", Set.of("page_preview", "preview_summary"), null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoFile reviewDownload(long projectId, long versionId, String artifactId) {
        return openFile(projectId, versionId, artifactId, "reviewPreview", Set.of("native_rp", "rp1", "audit", "engine_check"), null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProtoFile download(long projectId, long versionId, String artifactId, String rpSha256) {
        return openFile(projectId, versionId, artifactId, "download", Set.of("native_rp"), rpSha256);
    }

    private ProtoFile openFile(long projectId, long versionId, String artifactId, String action,
                               Set<String> kinds, String rpSha256) {
        var locked = lockVersion(projectId, versionId, action);
        if (locked.version().getArtifactManifest() == null) throw new HeronAccessException();
        var file = ProtoProductionDomain.readArtifacts(locked.version().getArtifactManifest()).stream()
            .filter(a -> Objects.equals(a.artifactId(), artifactId) && kinds.contains(a.kind())).findFirst()
            .orElseThrow(HeronAccessException::new);
        var manifest = domain.verifyVersion(locked.version(), locked.task());
        if ("download".equals(action)) {
            requireApproved(locked.version(), manifest);
            requireDeliveryHashes(locked.version(), manifest, rpSha256);
        }
        return openVerifiedFile(locked.version(), file);
    }

    private ProtoFile openVerifiedFile(HeronProtoVersion version, ProductionArtifactVo file) {
        artifacts.verify(file);
        Path root = properties.getDataRoot().toAbsolutePath().normalize();
        Path path = root.resolve(file.storageKey()).normalize();
        if (!path.startsWith(root)) conflict("产物文件路径不合法");
        try {
            // shortcut: SP1 仅提供 evidence 流，正式产物同句柄接口补齐后收敛此处。
            for (Path component = path; component != null; component = component.getParent()) {
                if (Files.isSymbolicLink(component)) conflict("产物文件不能使用符号链接");
            }
            if (!path.toRealPath().startsWith(root.toRealPath())) conflict("产物文件路径不合法");
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
                if (channel.size() != file.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
                    conflict("产物文件长度或哈希不符");
                }
                channel.position(0);
                String extension = file.kind().equals("native_rp") ? ".rp"
                    : file.mediaType().equals("image/png") ? ".png" : ".json";
                String name = "prototype-" + version.getProjectId() + "-v" + version.getVersionNo()
                    + "-" + file.kind() + extension;
                return new ProtoFile(name, file.mediaType(), file.bytes(), file.sha256(), Channels.newInputStream(channel));
            } catch (RuntimeException | IOException | NoSuchAlgorithmException e) {
                channel.close();
                throw e;
            }
        } catch (IOException e) {
            throw new HeronBusinessException(409, "proto_file_invalid", "产物文件不可读");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HeronProtoProject requireProject(long projectId, String action, boolean lock) {
        var project = lock ? projectMapper.lockById(projectId) : projectMapper.selectById(projectId);
        if (project == null) throw new HeronAccessException();
        access.requireObject(project.getOwnerId(), ProductionBizType.prototype, "heron:proto:" + action);
        return project;
    }

    private HeronProtoVersion requireVersion(HeronProtoProject project, long versionId, boolean lock) {
        var version = lock ? versionMapper.lockById(versionId) : versionMapper.selectById(versionId);
        if (version == null || !version.getProjectId().equals(project.getProjectId())
            || !version.getOwnerId().equals(project.getOwnerId())) throw new HeronAccessException();
        return version;
    }

    private LockedVersion lockVersion(long projectId, long versionId, String action) {
        var project = requireProject(projectId, action, true);
        var snapshot = requireVersion(project, versionId, false);
        var task = taskMapper.lockById(snapshot.getTaskId());
        var version = requireVersion(project, versionId, true);
        requireTask(version, task);
        return new LockedVersion(version, task);
    }

    private ProtoVersionVo toVersionVo(HeronProtoVersion version) {
        var task = taskMapper.selectById(version.getTaskId());
        requireTask(version, task);
        var vo = MapstructUtils.convert(version, ProtoVersionVo.class);
        vo.setStatus(task.getStatus());
        vo.setInput(ProtoProductionDomain.readInput(version.getInputSnapshot()));
        vo.setArtifacts(version.getArtifactManifest() == null ? List.of()
            : ProtoProductionDomain.readArtifacts(version.getArtifactManifest()).stream()
                .map(a -> new ProductionFileVo(a.artifactId(), a.kind(), a.bytes(), a.sha256(), a.mediaType(), true)).toList());
        vo.setAudit(version.getAuditResult() == null ? null : ProtoProductionDomain.readEvidence(version.getAuditResult()));
        vo.setNativeVerifications(verificationEvents(version).stream().map(NativeVerificationEvent::fact).toList());
        vo.setDelivery(deliveryVo(version));
        return vo;
    }

    private static void requireTask(HeronProtoVersion version, HeronProductionTask task) {
        if (task == null || !ProductionBizType.prototype.name().equals(task.getBizType())
            || !version.getTaskId().equals(task.getTaskId()) || !version.getProjectId().equals(task.getBizId())
            || !version.getVersionId().equals(task.getVersionId()) || !version.getOwnerId().equals(task.getOwnerId())) {
            throw new HeronAccessException();
        }
    }

    private static void requireApproved(HeronProtoVersion version, ProtoProductionDomain.ValidatedManifest manifest) {
        if (!"approved".equals(version.getReviewStatus()) || version.getReviewBy() == null || version.getReviewAt() == null
            || !manifest.rp().sha256().equals(version.getReviewRpSha())
            || !manifest.summary().sha256().equals(version.getReviewPreviewSha())) conflict("只有已审核的当前文件可交付");
    }

    private static void requireDeliveryHashes(HeronProtoVersion version, ProtoProductionDomain.ValidatedManifest manifest,
                                              String rpSha256) {
        requireHash(rpSha256);
        if (version.getDeliveryAt() == null || version.getDeliveryBy() == null || version.getDeliveryNativeVerified() == null
            || !Objects.equals(version.getDeliveryRpSha(), rpSha256)
            || !manifest.rp().sha256().equals(version.getDeliveryRpSha())
            || !manifest.summary().sha256().equals(version.getDeliveryPreviewSha())) conflict("交付记录不存在或文件哈希已变化");
    }

    private static void requireHashes(ProtoProductionDomain.ValidatedManifest manifest, String rp, String preview) {
        requireHash(rp);
        requireHash(preview);
        if (!manifest.rp().sha256().equals(rp) || !manifest.summary().sha256().equals(preview)) conflict("文件已变化，请刷新后操作");
    }

    private static ProtoDeliveryVo deliveryVo(HeronProtoVersion version) {
        if (version.getDeliveryAt() == null) return null;
        return new ProtoDeliveryVo(version.getDeliveryBy(), version.getDeliveryAt().toInstant(ZoneOffset.UTC),
            version.getDeliveryRpSha(), version.getDeliveryPreviewSha(), Boolean.TRUE.equals(version.getDeliveryNativeVerified()),
            version.getDeliveryVerificationId());
    }

    private static List<NativeVerificationEvent> verificationEvents(HeronProtoVersion version) {
        String json = version.getNativeVerificationJson();
        if (json == null) return new ArrayList<>();
        try {
            return new ArrayList<>(Arrays.asList(JSON.readValue(json, NativeVerificationEvent[].class)));
        } catch (tools.jackson.core.JacksonException e) {
            throw new HeronBusinessException(409, "proto_verification_invalid", "原生验证历史损坏，不能覆盖");
        }
    }

    private static HeronProtoProject normalizedDraft(ProtoProjectBo bo) {
        if (bo == null) invalid("缺少项目草稿");
        var entity = MapstructUtils.convert(bo, HeronProtoProject.class);
        entity.setName(normalizeText(bo.getName(), "项目名称", 1, 100));
        entity.setRequirement(normalizeText(bo.getRequirement(), "需求", 1, 8000));
        if (bo.getCanvas() == null || !Set.of("web", "mobile").contains(bo.getCanvas())) invalid("画布只能为 web/mobile");
        if (bo.getThemeKey() != null && !"default".equals(bo.getThemeKey())) invalid("主题尚未通过支持基准");
        entity.setThemeKey("default");
        return entity;
    }

    /** 基座 trim 不包含 NFC/code point 和 BOM/零宽边界语义，仅在原型输入边界补齐。 */
    static String normalizeText(String value, String field, int min, int max) {
        String text = Normalizer.normalize(value == null ? "" : value.replace("\r\n", "\n"), Normalizer.Form.NFC);
        int start = 0;
        int end = text.length();
        while (start < end && boundarySpace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && boundarySpace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        text = text.substring(start, end);
        int count = text.codePointCount(0, text.length());
        if (count < min || count > max || text.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
            invalid(field + "必须为 " + min + "–" + max + " 个 Unicode 字符");
        }
        return text;
    }

    private static boolean boundarySpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x0085 || cp == 0xFEFF
            || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060;
    }
    private static void requireRequestKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) invalid("请求键必须为 1–64 位 ASCII 标识");
    }
    private static void requireHash(String hash) { if (!ProtoProductionDomain.isHash(hash)) invalid("文件 SHA256 不合法"); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); }
    private static void invalid(String message) { throw new HeronBusinessException(400, "proto_invalid_input", message); }
    private static void conflict(String message) { throw new HeronBusinessException(409, "proto_state_conflict", message); }

    private record LockedVersion(HeronProtoVersion version, HeronProductionTask task) { }
    /** 持久保存完整显式声明，供同 requestKey 的内容比较；不向前端泄漏额外内部状态。 */
    public record NativeVerificationEvent(ProtoNativeVerificationBo declaration, ProtoNativeVerificationVo fact) { }
}
