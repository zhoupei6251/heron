package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.*;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.mapper.*;
import org.dromara.heron.service.INewsWorkService;
import org.dromara.heron.service.IProductionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** 新闻短事务：lane → candidate → work → task → version。清理标记分事务持久化。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsWorkServiceImpl implements INewsWorkService {
    private static final ProductionBizType TYPE = ProductionBizType.news_video;
    private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:[.][0-9]+)?");
    private final HeronNewsCandidateMapper candidateMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final HeronProductionLaneMapper laneMapper;
    private final HeronProductionTaskMapper taskMapper;
    private final HeronAccessService access;
    private final IProductionService production;
    private final NewsProductionDomain domain;
    private final ProductionArtifactService artifacts;
    private final PlatformTransactionManager transactionManager;
    private final ObjectProvider<Clock> clocks;

    @Override
    public PageResult<NewsCandidateVo> candidates(NewsCandidateQuery query) {
        access.requirePermission("heron:newsCandidate:list");
        var wrapper = QueryBuilder.lambda(HeronNewsCandidate.class).likeIfText(HeronNewsCandidate::getTitle, query.getTitle())
            .betweenParams(HeronNewsCandidate::getLastSeenAt, query.getParams(), "beginLastSeenAt", "endLastSeenAt")
            .orderByDesc(HeronNewsCandidate::getLastSeenAt, HeronNewsCandidate::getCandidateId).build();
        if (query.getClaimed() != null) {
            if (query.getClaimed()) wrapper.isNotNull(HeronNewsCandidate::getClaimedBy);
            else wrapper.isNull(HeronNewsCandidate::getClaimedBy);
        }
        Page<NewsCandidateVo> page = candidateMapper.selectVoPage(query.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public NewsCandidateVo candidate(long candidateId) {
        access.requirePermission("heron:newsCandidate:query");
        NewsCandidateVo vo = candidateMapper.selectVoById(candidateId);
        if (vo == null) throw new HeronAccessException();
        return vo;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsCandidateVo addManual(NewsCandidateBo bo) {
        access.requirePermission("heron:newsCandidate:manual");
        if (bo == null) throw invalid("手工输入不能为空");
        text(bo.getTitle(), 200, "标题");
        text(bo.getSummary(), 16000, "摘要");
        String title = bo.getTitle().strip();
        String summary = bo.getSummary().replace("\r\n", "\n").replace('\r', '\n').strip();
        String normalized = normalizedTitle(title);
        if (normalized.isEmpty() || normalized.length() > 200) throw invalid("规范化标题为空或过长");
        String key = DigestUtil.sha256Hex(normalized);
        String contentHash = DigestUtil.sha256Hex(normalized + "\n" + summary);
        lockLane();
        HeronNewsCandidate candidate = candidateMapper.selectOne(QueryBuilder.lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getDedupKey, key).build());
        if (candidate == null) {
            var recent = candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
                .ge(HeronNewsCandidate::getLastSeenAt, now().minusHours(48))
                .orderByDesc(HeronNewsCandidate::getLastSeenAt, HeronNewsCandidate::getCandidateId).last("LIMIT 5000").build());
            candidate = recent.stream().filter(c -> sameNews(normalized, c.getNormalizedTitle())).findFirst().orElse(null);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceId", null);
        evidence.put("sourceUrl", null);
        evidence.put("manual", true);
        evidence.put("fetchedAt", now().toString());
        evidence.put("title", title);
        evidence.put("summary", summary);
        evidence.put("sourceItemId", contentHash);
        evidence.put("contentSha256", contentHash);
        if (candidate == null) {
            candidate = MapstructUtils.convert(bo, HeronNewsCandidate.class);
            candidate.setCandidateId(IdUtil.getSnowflakeNextId());
            candidate.setTitle(title);
            candidate.setSummary(summary);
            candidate.setNormalizedTitle(normalized);
            candidate.setDedupKey(key);
            candidate.setHitCount(1L);
            candidate.setHeatScore(1L);
            candidate.setFirstSeenAt(now());
            candidate.setLastSeenAt(now());
            candidate.setEvidence(JsonUtils.toJsonString(Map.of("items", List.of(evidence), "evidenceTruncated", false)));
            candidate.setCreateBy(access.requireActor());
            candidate.setUpdateBy(access.requireActor());
            candidate.setCreateTime(now());
            candidate.setUpdateTime(now());
            if (candidateMapper.insert(candidate) != 1) throw conflict("candidate_write", "候选保存失败");
        } else {
            candidate = candidateMapper.lockById(candidate.getCandidateId());
            JsonNode stored = JsonUtils.getJsonMapper().readTree(candidate.getEvidence());
            List<Map<String, Object>> items = new ArrayList<>();
            boolean repeated = false;
            for (JsonNode item : stored.path("items")) {
                if (contentHash.equals(item.path("contentSha256").asText()) && item.path("manual").asBoolean()) repeated = true;
                items.add(new LinkedHashMap<>(JsonUtils.parseMap(JsonUtils.toJsonString(item))));
            }
            boolean truncated = stored.path("evidenceTruncated").asBoolean();
            if (!repeated) items.add(evidence);
            while (items.size() > 100 || JsonUtils.toJsonString(items).getBytes(StandardCharsets.UTF_8).length > 240 * 1024) {
                // Preserve the first and newest evidence, discard intermediate entries only.
                if (items.size() <= 2) throw invalid("来源证据过大");
                items.remove(1);
                truncated = true;
            }
            candidateMapper.lambda().set(HeronNewsCandidate::getEvidence,
                    JsonUtils.toJsonString(Map.of("items", items, "evidenceTruncated", truncated)))
                .set(HeronNewsCandidate::getHitCount, repeated ? candidate.getHitCount() : Math.addExact(candidate.getHitCount(), 1L))
                .set(HeronNewsCandidate::getHeatScore, repeated ? candidate.getHeatScore() : Math.addExact(candidate.getHeatScore(), 1L))
                .set(HeronNewsCandidate::getLastSeenAt, now()).set(HeronNewsCandidate::getUpdateBy, access.requireActor())
                .set(HeronNewsCandidate::getUpdateTime, now()).eq(HeronNewsCandidate::getCandidateId, candidate.getCandidateId()).update();
        }
        return candidateMapper.selectVoById(candidate.getCandidateId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsWorkVo claim(long candidateId) {
        access.requirePermission("heron:newsCandidate:claim");
        long actor = access.requireActor();
        lockLane();
        HeronNewsCandidate candidate = candidateMapper.lockById(candidateId);
        if (candidate == null) throw new HeronAccessException();
        HeronNewsWork existing = workMapper.byCandidate(candidateId);
        if (existing != null) {
            if (existing.getOwnerId() != actor) throw conflict("already_claimed", "候选已被其他用户认领");
            return workView(existing);
        }
        if (candidate.getClaimedBy() != null) throw conflict("already_claimed", "候选已被认领");
        HeronNewsWork work = new HeronNewsWork();
        work.setWorkId(IdUtil.getSnowflakeNextId());
        work.setCandidateId(candidateId);
        work.setOwnerId(actor);
        work.setTitle(candidate.getTitle());
        work.setDraft(candidate.getSummary());
        work.setRevision(1L);
        work.setNextVersion(1);
        work.setTemplateKey("default");
        work.setVoiceKey("default");
        work.setCreateBy(actor);
        work.setUpdateBy(actor);
        work.setCreateTime(now());
        work.setUpdateTime(now());
        if (workMapper.insert(work) != 1) throw conflict("claim_failed", "作品创建失败");
        if (candidateMapper.lambda().set(HeronNewsCandidate::getClaimedBy, actor).set(HeronNewsCandidate::getClaimedAt, now())
            .set(HeronNewsCandidate::getUpdateBy, actor).set(HeronNewsCandidate::getUpdateTime, now())
            .eq(HeronNewsCandidate::getCandidateId, candidateId).isNull(HeronNewsCandidate::getClaimedBy).updateCount() != 1) {
            throw conflict("already_claimed", "候选已被认领");
        }
        return workView(work);
    }

    @Override
    public PageResult<NewsWorkVo> queryPageList(NewsWorkQuery query) {
        access.requirePermission("heron:newsWork:list");
        var wrapper = QueryBuilder.lambda(HeronNewsWork.class).likeIfText(HeronNewsWork::getTitle, query.getTitle())
            .eqIfPresent(HeronNewsWork::getCandidateId, query.getCandidateId())
            .eq(!access.hasAllScope(TYPE), HeronNewsWork::getOwnerId, access.requireActor())
            .betweenParams(HeronNewsWork::getUpdateTime, query.getParams(), "beginUpdateTime", "endUpdateTime")
            .orderByDesc(HeronNewsWork::getUpdateTime, HeronNewsWork::getWorkId).build();
        Page<NewsWorkVo> page = workMapper.selectVoPage(query.build(), wrapper);
        page.getRecords().forEach(this::enrichWork);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public NewsWorkVo queryById(long workId) { return workView(requireWork(workId, "query")); }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsWorkVo saveDraft(long workId, NewsDraftBo bo) {
        requireWork(workId, "edit");
        if (bo == null || bo.expectedRevision() == null || bo.expectedRevision() < 1 || bo.expectedRevision() == Long.MAX_VALUE) throw invalid("草稿 revision 不合法");
        text(bo.title(), 200, "标题");
        text(bo.draft(), 16000, "草稿");
        if (bo.templateKey() == null || !bo.templateKey().matches("[a-zA-Z0-9_-]{1,80}")
            || bo.voiceKey() == null || !bo.voiceKey().matches("[a-zA-Z0-9_-]{1,80}")) throw invalid("模板或声音标识不合法");
        lockLane();
        domain.lockObject(workId);
        HeronNewsWork updated = MapstructUtils.convert(bo, HeronNewsWork.class);
        int changed = workMapper.lambda().set(HeronNewsWork::getTitle, updated.getTitle().strip())
            .set(HeronNewsWork::getDraft, updated.getDraft().replace("\r\n", "\n").replace('\r', '\n'))
            .set(HeronNewsWork::getTemplateKey, updated.getTemplateKey()).set(HeronNewsWork::getVoiceKey, updated.getVoiceKey())
            .set(HeronNewsWork::getRevision, bo.expectedRevision() + 1).set(HeronNewsWork::getUpdateBy, access.requireActor())
            .set(HeronNewsWork::getUpdateTime, now()).eq(HeronNewsWork::getWorkId, workId)
            .eq(HeronNewsWork::getRevision, bo.expectedRevision()).updateCount();
        if (changed != 1) throw conflict("revision_conflict", "草稿已被其他操作更新");
        return workView(workMapper.selectById(workId));
    }

    @Override
    public ProductionReceiptVo generate(long workId, NewsGenerateBo bo) {
        requireWork(workId, "generate");
        if (bo == null || bo.expectedRevision() == null || bo.requestKey() == null || !bo.requestKey().matches("[a-zA-Z0-9._:-]{1,64}")) throw invalid("生成请求不合法");
        return production.submit(new ProductionSubmitBo(TYPE, workId, bo.requestKey(), bo.expectedRevision()));
    }

    @Override
    public PageResult<NewsVideoVersionVo> versions(long workId, PageQuery pageQuery) {
        HeronNewsWork work = requireWork(workId, "query");
        Page<NewsVideoVersionVo> page = versionMapper.selectVoPage(pageQuery.build(), QueryBuilder.lambda(HeronVideoVersion.class)
            .eq(HeronVideoVersion::getWorkId, workId).eq(HeronVideoVersion::getOwnerId, work.getOwnerId())
            .orderByDesc(HeronVideoVersion::getVersionNo).build());
        return PageResult.build(page.getRecords().stream().map(vo -> versionView(versionMapper.selectById(vo.getVersionId()))).toList(), page.getTotal());
    }

    @Override
    public NewsVideoVersionVo version(long workId, long versionId) {
        return versionView(requireVersion(requireWork(workId, "query"), versionId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsVideoVersionVo review(long workId, long versionId, NewsReviewBo bo) {
        HeronVideoVersion version = lockVersion(workId, versionId, "review");
        if (bo == null || !Set.of("approved", "rejected").contains(Objects.toString(bo.decision(), ""))
            || !sha(bo.contactSha256()) || !sha(bo.videoSha256()) || bo.reason() != null && bo.reason().length() > 1000
            || "rejected".equals(bo.decision()) && (bo.reason() == null || bo.reason().isBlank())) throw invalid("审核决议、双指纹或驳回理由不合法");
        List<ProductionArtifactVo> files = readyFiles(version);
        ProductionArtifactVo video = byKind(files, "mp4"), contact = byKind(files, "contact_sheet");
        if (!video.sha256().equals(bo.videoSha256()) || !contact.sha256().equals(bo.contactSha256())) throw conflict("review_hash", "审核指纹与该版本不一致");
        if (!"pending".equals(version.getReviewStatus())) {
            if (version.getReviewStatus().equals(bo.decision()) && Objects.equals(version.getReviewContactSha(), bo.contactSha256())
                && Objects.equals(version.getReviewVideoSha(), bo.videoSha256())) return versionView(version);
            throw conflict("review_conflict", "已有审核决议：" + version.getReviewStatus());
        }
        if (versionMapper.lambda().set(HeronVideoVersion::getReviewStatus, bo.decision())
            .set(HeronVideoVersion::getReviewBy, access.requireActor()).set(HeronVideoVersion::getReviewAt, now())
            .set(HeronVideoVersion::getReviewReason, bo.reason() == null ? null : bo.reason().strip())
            .set(HeronVideoVersion::getReviewContactSha, bo.contactSha256()).set(HeronVideoVersion::getReviewVideoSha, bo.videoSha256())
            .set(HeronVideoVersion::getUpdateBy, access.requireActor()).set(HeronVideoVersion::getUpdateTime, now())
            .eq(HeronVideoVersion::getVersionId, versionId).eq(HeronVideoVersion::getReviewStatus, "pending")
            .isNull(HeronVideoVersion::getReviewContactSha).isNull(HeronVideoVersion::getReviewVideoSha)
            .isNull(HeronVideoVersion::getCleanupStartedAt).updateCount() != 1) throw conflict("review_conflict", "审核状态已变化");
        return versionView(versionMapper.selectById(versionId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsFileVo preview(long workId, long versionId, String artifactId) {
        HeronVideoVersion version = lockVersion(workId, versionId, "reviewPreview");
        ProductionArtifactVo selected = byId(parseManifest(version), artifactId);
        if (!Set.of("mp4", "contact_sheet").contains(selected.kind())) throw new HeronAccessException();
        readyFiles(version);
        return domain.openFile(version, selected);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsFileVo download(long workId, long versionId, String artifactId, String videoSha256) {
        HeronVideoVersion version = lockVersion(workId, versionId, "download");
        ProductionArtifactVo selected = byId(parseManifest(version), artifactId);
        if (!"mp4".equals(selected.kind())) throw new HeronAccessException();
        List<ProductionArtifactVo> files = readyFiles(version);
        if (!"approved".equals(version.getReviewStatus()) || !Objects.equals(selected.sha256(), videoSha256)
            || !Objects.equals(version.getReviewVideoSha(), selected.sha256())
            || !Objects.equals(version.getReviewContactSha(), byKind(files, "contact_sheet").sha256())) throw conflict("download_unapproved", "需审核通过且两个指纹仍匹配");
        return domain.openFile(version, selected);
    }

    /** 仅内部显式调用；无默认调度、无 HTTP 删除接口。每项失败返回安全原因，审核事实不变。 */
    public List<NewsCleanupVo> cleanupExpired(boolean dryRun) {
        List<HeronVideoVersion> candidates = versionMapper.selectList(QueryBuilder.lambda(HeronVideoVersion.class)
            .le(HeronVideoVersion::getCreateTime, now().minusDays(30)).isNull(HeronVideoVersion::getCleanedAt)
            .isNotNull(HeronVideoVersion::getArtifactManifest).orderByAsc(HeronVideoVersion::getVersionId).build());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<NewsCleanupVo> result = new ArrayList<>();
        for (HeronVideoVersion candidate : candidates) {
            try {
                Boolean eligible = tx.execute(status -> {
                    HeronVideoVersion version = lockMaintenanceVersion(candidate.getWorkId(), candidate.getVersionId());
                    if (!cleanupEligible(version)) return false;
                    if (!dryRun && version.getCleanupStartedAt() == null) {
                        if (versionMapper.lambda().set(HeronVideoVersion::getCleanupStartedAt, now())
                            .set(HeronVideoVersion::getUpdateTime, now()).eq(HeronVideoVersion::getVersionId, version.getVersionId())
                            .isNull(HeronVideoVersion::getCleanupStartedAt).isNull(HeronVideoVersion::getCleanedAt).updateCount() != 1) {
                            throw conflict("cleanup_conflict", "清理状态已变化");
                        }
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(eligible)) continue;
                if (dryRun) { result.add(new NewsCleanupVo(candidate.getVersionId(), false, null)); continue; }
                Boolean cleaned = tx.execute(status -> {
                    HeronVideoVersion version = lockMaintenanceVersion(candidate.getWorkId(), candidate.getVersionId());
                    if (!cleanupEligible(version) || version.getCleanupStartedAt() == null) return false;
                    // Partial previous deletion is resumable; existing files must still pass the core verification.
                    for (ProductionArtifactVo file : parseManifest(version)) {
                        Path path = domain.safePath(file);
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) artifacts.removeFile(file);
                    }
                    if (versionMapper.lambda().set(HeronVideoVersion::getCleanedAt, now()).set(HeronVideoVersion::getUpdateTime, now())
                        .eq(HeronVideoVersion::getVersionId, version.getVersionId()).isNotNull(HeronVideoVersion::getCleanupStartedAt)
                        .isNull(HeronVideoVersion::getCleanedAt).updateCount() != 1) throw conflict("cleanup_conflict", "清理状态已变化");
                    return true;
                });
                result.add(new NewsCleanupVo(candidate.getVersionId(), Boolean.TRUE.equals(cleaned), null));
            } catch (RuntimeException e) {
                String code = e instanceof HeronBusinessException business ? business.getCode() : "news_cleanup_failed";
                log.warn("News cleanup did not finish: versionId={}, code={}", candidate.getVersionId(), code);
                result.add(new NewsCleanupVo(candidate.getVersionId(), false, code));
            }
        }
        return List.copyOf(result);
    }

    private boolean cleanupEligible(HeronVideoVersion version) {
        if (version == null || version.getCleanedAt() != null || version.getCreateTime().isAfter(now().minusDays(30))) return false;
        HeronNewsWork work = workMapper.selectById(version.getWorkId());
        HeronNewsCandidate candidate = candidateMapper.selectById(work.getCandidateId());
        if (work.getActiveTaskId() != null || candidate.getActiveTaskId() != null) return false;
        if (taskMapper.selectCount(QueryBuilder.lambda(HeronProductionTask.class).eq(HeronProductionTask::getBizType, TYPE.name())
            .eq(HeronProductionTask::getBizId, work.getWorkId()).in(HeronProductionTask::getStatus, List.of("queued", "running", "cancelling")).build()) > 0) return false;
        HeronProductionTask task = taskMapper.selectById(version.getTaskId());
        domain.validateManifest(version, task, parseManifest(version), false);
        return !domain.hasKeep(task, parseManifest(version));
    }

    private HeronVideoVersion lockMaintenanceVersion(long workId, long versionId) {
        lockLane();
        domain.lockObject(workId);
        HeronVideoVersion snapshot = versionMapper.selectById(versionId);
        if (snapshot == null || snapshot.getWorkId() != workId) throw new HeronAccessException();
        HeronProductionTask task = taskMapper.lockById(snapshot.getTaskId());
        HeronVideoVersion version = versionMapper.lockById(versionId);
        domain.requireTask(version, task);
        return version;
    }

    private HeronVideoVersion lockVersion(long workId, long versionId, String action) {
        HeronNewsWork work = requireWork(workId, action);
        requireVersion(work, versionId);
        return lockMaintenanceVersion(workId, versionId);
    }

    private HeronNewsWork requireWork(long id, String action) {
        HeronNewsWork work = workMapper.selectById(id);
        if (work == null) throw new HeronAccessException();
        access.requireObject(work.getOwnerId(), TYPE, "heron:newsWork:" + action);
        return work;
    }

    private HeronVideoVersion requireVersion(HeronNewsWork work, long id) {
        HeronVideoVersion version = versionMapper.selectById(id);
        if (version == null || !Objects.equals(version.getWorkId(), work.getWorkId())
            || !Objects.equals(version.getOwnerId(), work.getOwnerId())) throw new HeronAccessException();
        domain.requireTask(version, taskMapper.selectById(version.getTaskId()));
        return version;
    }

    private List<ProductionArtifactVo> readyFiles(HeronVideoVersion version) {
        if (version.getCleanedAt() != null || version.getCleanupStartedAt() != null) throw new HeronBusinessException(410, "news_files_expired", "文件已清理或正在清理");
        HeronProductionTask task = taskMapper.selectById(version.getTaskId());
        domain.requireTask(version, task);
        if (!"succeeded".equals(task.getStatus()) || version.getScript() == null || version.getDurationMs() == null || version.getDurationMs() <= 0) {
            throw conflict("not_complete", "生产任务尚未成功完成");
        }
        domain.validateChecks(version.getChecks());
        List<ProductionArtifactVo> files = parseManifest(version);
        domain.validateManifest(version, task, files, false);
        for (ProductionArtifactVo file : files) {
            if (!Files.exists(domain.safePath(file), LinkOption.NOFOLLOW_LINKS)) {
                throw new HeronBusinessException(410, "news_files_expired", "版本文件已不存在");
            }
        }
        domain.validateManifest(version, task, files, true);
        domain.verifyReports(files, version.getScript(), version.getChecks());
        return files;
    }

    private NewsWorkVo workView(HeronNewsWork work) {
        NewsWorkVo vo = MapstructUtils.convert(work, NewsWorkVo.class);
        enrichWork(vo);
        return vo;
    }

    private void enrichWork(NewsWorkVo vo) {
        if (vo.getCurrentVersionId() != null) {
            HeronVideoVersion version = versionMapper.selectById(vo.getCurrentVersionId());
            if (version == null || !Objects.equals(version.getWorkId(), vo.getWorkId()) || !Objects.equals(version.getOwnerId(), vo.getOwnerId())) throw new HeronAccessException();
            vo.setCurrentVersion(versionView(version));
        }
        if (vo.getActiveTaskId() != null) {
            HeronProductionTask task = taskMapper.selectById(vo.getActiveTaskId());
            if (task == null || !TYPE.name().equals(task.getBizType()) || !Objects.equals(task.getBizId(), vo.getWorkId())
                || !Objects.equals(task.getOwnerId(), vo.getOwnerId())) throw new HeronAccessException();
            ProductionTaskVo active = MapstructUtils.convert(task, ProductionTaskVo.class);
            active.setErrorReason(null);
            vo.setActiveTask(active);
        }
    }

    private NewsVideoVersionVo versionView(HeronVideoVersion version) {
        HeronProductionTask task = taskMapper.selectById(version.getTaskId());
        domain.requireTask(version, task);
        NewsVideoVersionVo vo = MapstructUtils.convert(version, NewsVideoVersionVo.class);
        vo.setTaskStatus(task.getStatus());
        List<ProductionArtifactVo> files = parseManifest(version);
        if (!files.isEmpty()) domain.validateManifest(version, task, files, false);
        boolean retained = version.getCleanedAt() == null && version.getCleanupStartedAt() == null;
        vo.setArtifacts(files.stream().map(file -> {
            boolean available = retained;
            if (available) {
                try { domain.safePath(file); artifacts.verify(file); }
                catch (HeronBusinessException e) { available = false; }
            }
            return new ProductionFileVo(file.artifactId(), file.kind(), file.bytes(), file.sha256(), file.mediaType(), available);
        }).toList());
        return vo;
    }

    private List<ProductionArtifactVo> parseManifest(HeronVideoVersion version) {
        try { return JsonUtils.parseArray(version.getArtifactManifest(), ProductionArtifactVo.class); }
        catch (RuntimeException e) { throw conflict("manifest_invalid", "产物清单不合法"); }
    }
    private ProductionArtifactVo byKind(List<ProductionArtifactVo> files, String kind) {
        return files.stream().filter(a -> a != null && kind.equals(a.kind())).findFirst().orElseThrow(() -> conflict("manifest_incomplete", "产物清单不完整"));
    }
    private ProductionArtifactVo byId(List<ProductionArtifactVo> files, String id) {
        return files.stream().filter(a -> a != null && Objects.equals(id, a.artifactId())).findFirst().orElseThrow(HeronAccessException::new);
    }
    private void lockLane() { if (laneMapper.lockByType(TYPE) == null) throw new HeronBusinessException(503, "news_lane_missing", "新闻生产线未初始化"); }
    private LocalDateTime now() { return LocalDateTime.now(clocks.getIfAvailable(Clock::systemUTC)); }
    private boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private void text(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) throw invalid(name + "为空或超长");
    }
    private HeronBusinessException invalid(String message) { return new HeronBusinessException(400, "news_invalid_input", message); }
    private HeronBusinessException conflict(String code, String message) { return new HeronBusinessException(409, "news_" + code, message); }

    private String normalizedTitle(String title) {
        // Numeric punctuation is an anchor: stripping 1.2 into 12 would merge different facts.
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Z}\\s]+", "")
            .replaceAll("(?<![0-9])[\\p{P}]|[\\p{P}](?![0-9])", "");
    }
    private boolean sameNews(String left, String right) {
        if (left.equals(right)) return true;
        if (left.codePointCount(0, left.length()) < 6 || right.codePointCount(0, right.length()) < 6
            || !anchors(left).equals(anchors(right))) return false;
        Set<String> a = trigrams(left), b = trigrams(right);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= 0.88;
    }
    private Set<String> anchors(String title) { return new HashSet<>(NUMBERS.matcher(title).results().map(match -> match.group()).toList()); }
    private Set<String> trigrams(String title) {
        int[] points = title.codePoints().toArray();
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= points.length - 3; i++) result.add(new String(points, i, 3));
        return result;
    }
}
