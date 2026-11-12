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
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.*;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.mapper.*;
import org.dromara.heron.service.INewsWorkService;
import org.dromara.heron.service.IPublishAdapter;
import org.dromara.heron.service.IProductionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 新闻短事务：lane → candidate → work → task → version。清理标记分事务持久化。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsWorkServiceImpl implements INewsWorkService {
    private static final ProductionBizType TYPE = ProductionBizType.news_video;
    /** 自动发布只认这些审稿级别；白名单之外的拼写一律当非法，不猜风险高低。 */
    private static final java.util.Set<String> REVIEW_SEVERITIES = java.util.Set.of("BLOCKER", "MAJOR", "MINOR");
    /** 当前审稿记录契约版本；历史记录没有版本号的一律不能自动发布。 */
    private static final java.util.Set<String> REVIEW_SCHEMAS = java.util.Set.of("1.0");
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
    private final IPublishAdapter publishAdapter;
    private final NewsTemplatePicker templatePicker;
    private final HeronNewsProperties news;

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
        String normalized = NewsDedup.normalizedTitle(title);
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
            candidate = recent.stream().filter(c -> NewsDedup.sameNews(normalized, c.getNormalizedTitle()))
                .findFirst().orElse(null);
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
            var merged = NewsDedup.merge(candidate.getEvidence(), evidence,
                (stored, incoming) -> contentHash.equals(stored.get("contentSha256")) && Boolean.TRUE.equals(stored.get("manual")));
            candidateMapper.lambda().set(HeronNewsCandidate::getEvidence, merged.evidence())
                .set(HeronNewsCandidate::getHitCount,
                    merged.repeated() ? candidate.getHitCount() : Math.addExact(candidate.getHitCount(), 1L))
                .set(HeronNewsCandidate::getHeatScore,
                    merged.repeated() ? candidate.getHeatScore() : Math.addExact(candidate.getHeatScore(), 1L))
                .set(HeronNewsCandidate::getLastSeenAt, now()).set(HeronNewsCandidate::getUpdateBy, access.requireActor())
                .set(HeronNewsCandidate::getUpdateTime, now()).eq(HeronNewsCandidate::getCandidateId, candidate.getCandidateId()).update();
        }
        return candidateMapper.selectVoById(candidate.getCandidateId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsWorkVo claim(long candidateId) {
        access.requirePermission("heron:newsCandidate:claim");
        return claimAs(candidateId, access.requireActor(), false);
    }

    /** 自动模式认领：后台线程没有会话，用系统账号；标记 auto_publish=0 交给自动发布扫描。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsWorkVo claimForAuto(long candidateId, long actor) {
        return claimAs(candidateId, actor, true, null);
    }

    /**
     * 自动线认领：候选、模板、数据源、叙事角度来自同一次线内编辑，
     * 认领只是把它们落成一个作品，不再自己挑任何东西。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsWorkVo claimForAuto(EditorialSelection selection, long actor) {
        long candidateId = selection.candidateId() != null
            ? selection.candidateId()
            : placeholderCandidate(selection, actor);
        return claimAs(candidateId, actor, true, selection.templateId());
    }

    /**
     * 自动线的补偿：认领成功但提交失败时，把作品与候选从「本次尝试占用」里放出来。
     *
     * <p>不释放会怎样：候选的 claimed_by 一直挂着，建池查询过滤 `claimed_by is null`，
     * 这条候选从此再也不会被选中——失败一次就等于永久拉黑，且没有任何报错。
     *
     * <p>只回收「没产出过版本」的作品：已经出过片子的作品不能因为一次重试失败就被清掉认领。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void abandonAuto(long workId) {
        HeronNewsWork work = workMapper.selectById(workId);
        if (work == null || work.getCurrentVersionId() != null) {
            return;
        }
        Long candidateId = work.getCandidateId();
        workMapper.lambda().set(HeronNewsWork::getActiveTaskId, null)
            .set(HeronNewsWork::getUpdateBy, work.getOwnerId())
            .set(HeronNewsWork::getUpdateTime, now())
            .eq(HeronNewsWork::getWorkId, workId)
            .isNull(HeronNewsWork::getCurrentVersionId).update();
        if (candidateId == null) {
            return;
        }
        candidateMapper.lambda().set(HeronNewsCandidate::getClaimedBy, null)
            .set(HeronNewsCandidate::getClaimedAt, null)
            .set(HeronNewsCandidate::getActiveTaskId, null)
            .set(HeronNewsCandidate::getUpdateBy, work.getOwnerId())
            .set(HeronNewsCandidate::getUpdateTime, now())
            .eq(HeronNewsCandidate::getCandidateId, candidateId)
            .eq(HeronNewsCandidate::getClaimedBy, work.getOwnerId())
            .isNull(HeronNewsCandidate::getPublishedAt).update();
    }

    /**
     * 数据看板线的候选概念是数据集，没有新闻候选可认领。
     * 这里建一条占位候选承载证据与出处，让作品与版本仍挂在同一条链上——
     * shortcut: 占位候选不进入热榜池，靠 publishedAt 由作品发布时回填。
     */
    private long placeholderCandidate(EditorialSelection selection, long actor) {
        HeronNewsCandidate placeholder = new HeronNewsCandidate();
        placeholder.setCandidateId(IdUtil.getSnowflakeNextId());
        placeholder.setTitle("数据看板 · " + selection.dataSourceKey());
        placeholder.setSummary("数据看板线由数据集驱动，非新闻候选");
        placeholder.setEvidence(JsonUtils.toJsonString(java.util.Map.of("items", java.util.List.of())));
        placeholder.setHitCount(1L);
        placeholder.setHeatScore(0L);
        placeholder.setFirstSeenAt(now());
        placeholder.setLastSeenAt(now());
        placeholder.setCreateBy(actor);
        placeholder.setUpdateBy(actor);
        placeholder.setCreateTime(now());
        placeholder.setUpdateTime(now());
        if (candidateMapper.insert(placeholder) != 1) throw conflict("claim_failed", "数据看板候选创建失败");
        return placeholder.getCandidateId();
    }

    /** 总编辑已定线时由调用方给死模板，覆盖配置里的默认模板。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsWorkVo claimForAuto(long candidateId, long actor, String templateKey) {
        return claimAs(candidateId, actor, true, templateKey);
    }

    private NewsWorkVo claimAs(long candidateId, long actor, boolean auto) {
        return claimAs(candidateId, actor, auto, null);
    }

    private NewsWorkVo claimAs(long candidateId, long actor, boolean auto, String forcedTemplate) {
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
        work.setAutoPublish(auto ? "0" : "1");
        Map<String, Object> locked = domain.lockedAssetsIfConfigured();
        // 总编辑定的线优先：给了死模板就不再看配置里的默认模板
        String pinned = forcedTemplate != null && !forcedTemplate.isBlank()
            ? forcedTemplate : news.getAutoTemplate();
        String picked;
        if (auto && pinned != null && !pinned.isBlank()) {
            if (!domain.availableTemplates().contains(pinned)) {
                throw new HeronBusinessException(503, "auto_template_missing",
                    "自动模板不存在或缺少 LOCK.json：" + pinned);
            }
            picked = pinned;
        } else {
            // 人工认领仍由模型挑，系统再卡「最近两期不重复」；挑不出来就落到最久未用的那套
            picked = templatePicker.pick(candidate.getTitle(), candidate.getSummary());
        }
        work.setTemplateKey(picked != null ? picked : (String) locked.getOrDefault("templateKey", "default"));
        work.setVoiceKey((String) locked.getOrDefault("voiceKey", "default"));
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

    /** 自动模式提交：跳过对象级权限校验，决策与选择随提交一起进快照。 */
    public ProductionReceiptVo generateForAuto(long workId, String requestKey,
                                               EditorialDecisionService.Decision decision,
                                               EditorialSelection selection) {
        HeronNewsWork work = workMapper.selectById(workId);
        if (work == null) throw new HeronAccessException();
        if (requestKey == null || !requestKey.matches("[a-zA-Z0-9._:-]{1,64}")) throw invalid("生成请求不合法");
        return production.submit(new ProductionSubmitBo(TYPE, workId, requestKey, work.getRevision(),
            decision, selection));
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

    /**
     * 审片队列只按 review_status 过滤，不预先剔除渲染失败的版本：把它藏起来会让
     * 「生成了却没得审」变成看不见的问题。页面按 taskStatus 区分能不能审。
     */
    @Override
    public PageResult<NewsVideoVersionVo> reviewQueue(PageQuery pageQuery) {
        access.requirePermission("heron:newsWork:review");
        Page<HeronVideoVersion> page = versionMapper.selectPage(pageQuery.build(), QueryBuilder.lambda(HeronVideoVersion.class)
            .eq(HeronVideoVersion::getReviewStatus, "pending")
            .eq(!access.hasAllScope(TYPE), HeronVideoVersion::getOwnerId, access.requireActor())
            .orderByAsc(HeronVideoVersion::getCreateTime).build());
        List<Long> workIds = page.getRecords().stream().map(HeronVideoVersion::getWorkId).distinct().toList();
        Map<Long, String> titles = workIds.isEmpty() ? Map.of() : workMapper.selectByIds(workIds).stream()
            .collect(java.util.stream.Collectors.toMap(HeronNewsWork::getWorkId, HeronNewsWork::getTitle));
        List<NewsVideoVersionVo> rows = page.getRecords().stream().map(version -> {
            NewsVideoVersionVo vo = listVersionView(version);
            vo.setWorkTitle(titles.get(version.getWorkId()));
            return vo;
        }).toList();
        return PageResult.build(rows, page.getTotal());
    }

    /** 发布记录：只要落过发布事实就列出，沙箱与命令行提交也算——「发过没发过」要能一眼分清。 */
    @Override
    public PageResult<NewsVideoVersionVo> published(PageQuery pageQuery) {
        access.requirePermission("heron:newsWork:list");
        Page<HeronVideoVersion> page = versionMapper.selectPage(pageQuery.build(), QueryBuilder.lambda(HeronVideoVersion.class)
            .isNotNull(HeronVideoVersion::getPublishAt)
            .eq(!access.hasAllScope(TYPE), HeronVideoVersion::getOwnerId, access.requireActor())
            .orderByDesc(HeronVideoVersion::getPublishAt).build());
        List<Long> workIds = page.getRecords().stream().map(HeronVideoVersion::getWorkId).distinct().toList();
        Map<Long, String> titles = workIds.isEmpty() ? Map.of() : workMapper.selectByIds(workIds).stream()
            .collect(java.util.stream.Collectors.toMap(HeronNewsWork::getWorkId, HeronNewsWork::getTitle));
        List<NewsVideoVersionVo> rows = page.getRecords().stream().map(version -> {
            NewsVideoVersionVo vo = listVersionView(version);
            vo.setWorkTitle(titles.get(version.getWorkId()));
            return vo;
        }).toList();
        return PageResult.build(rows, page.getTotal());
    }

    /**
     * 列表不能因为某一行读不到任务就整页 500：任务被清理或跨版本不一致时退回只有版本事实的视图。
     * 单条详情仍然走版本视图，让不一致在需要严格的地方暴露出来。
     */
    private NewsVideoVersionVo listVersionView(HeronVideoVersion version) {
        NewsVideoVersionVo vo;
        try {
            vo = versionView(version);
        } catch (RuntimeException e) {
            log.debug("版本 {} 的任务不可读，退回基础视图: {}", version.getVersionId(), e.getMessage());
            vo = MapstructUtils.convert(version, NewsVideoVersionVo.class);
        }
        // 列表不展示正文：一条 inputSnapshot 含整篇正文与证据，一页十行就是几百 KB，打开队列要等好几秒
        vo.setInputSnapshot(null);
        vo.setScript(null);
        return vo;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsVideoVersionVo review(long workId, long versionId, NewsReviewBo bo) {
        return reviewAs(workId, versionId, bo, access.requireActor(), false);
    }

    private NewsVideoVersionVo reviewAs(long workId, long versionId, NewsReviewBo bo, long actor, boolean system) {
        HeronVideoVersion version = system ? lockVersionAny(workId, versionId) : lockVersion(workId, versionId, "review");
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
            .set(HeronVideoVersion::getReviewBy, actor).set(HeronVideoVersion::getReviewAt, now())
            .set(HeronVideoVersion::getReviewReason, bo.reason() == null ? null : bo.reason().strip())
            .set(HeronVideoVersion::getReviewContactSha, bo.contactSha256()).set(HeronVideoVersion::getReviewVideoSha, bo.videoSha256())
            .set(HeronVideoVersion::getUpdateBy, actor).set(HeronVideoVersion::getUpdateTime, now())
            .eq(HeronVideoVersion::getVersionId, versionId).eq(HeronVideoVersion::getReviewStatus, "pending")
            .isNull(HeronVideoVersion::getReviewContactSha).isNull(HeronVideoVersion::getReviewVideoSha)
            .isNull(HeronVideoVersion::getCleanupStartedAt).updateCount() != 1) throw conflict("review_conflict", "审核状态已变化");
        return versionView(versionMapper.selectById(versionId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsVideoVersionVo publish(long workId, long versionId, NewsPublishBo bo) {
        if (bo == null || bo.publishedAt() == null || !sha(bo.videoSha256()) || !douyinUrl(bo.workUrl())) {
            throw invalid("作品链接、发布时间或成片指纹不合法");
        }
        return applyReceipt(workId, versionId, bo.workUrl(), bo.account(), bo.publishedAt(), bo.videoSha256(),
            "manual", access.requireActor(), false);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsVideoVersionVo backfillPublishUrl(long workId, long versionId, NewsPublishUrlBo bo) {
        if (bo == null || !douyinUrl(bo.workUrl())) throw invalid("作品链接不合法");
        String url = bo.workUrl().strip();
        HeronVideoVersion version = lockVersion(workId, versionId, "publish");
        if (!DouyinCliPublishAdapter.SOURCE.equals(version.getPublishSource())) throw conflict("publish_source", "只有命令行发布的记录需要补链接");
        if (version.getPublishAt() == null) throw conflict("publish_not_submitted", "该版本尚未提交发布");
        if (version.getPublishUrl() != null) {
            if (version.getPublishUrl().equals(url)) return versionView(version);
            throw conflict("publish_conflict", "该版本已有作品链接");
        }
        if (versionMapper.lambda().set(HeronVideoVersion::getPublishUrl, url)
            .set(HeronVideoVersion::getUpdateBy, access.requireActor()).set(HeronVideoVersion::getUpdateTime, now())
            .eq(HeronVideoVersion::getVersionId, versionId).isNull(HeronVideoVersion::getPublishUrl)
            .updateCount() != 1) throw conflict("publish_conflict", "作品链接已变化");
        return versionView(versionMapper.selectById(versionId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsVideoVersionVo confirm(long workId, long versionId, NewsReviewBo bo) {
        return confirmAs(workId, versionId, bo, access.requireActor(), false);
    }

    /**
     * 自动模式：后台线程没有会话，用系统账号写确认与回执。
     * 走的仍是同一套校验与幂等规则，变的只有操作人来源。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public NewsVideoVersionVo confirmForAuto(long workId, long versionId, long actor) {
        HeronVideoVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new HeronAccessException();
        requireEditorialPass(version);
        List<ProductionArtifactVo> files = readyFiles(version);
        NewsReviewBo bo = new NewsReviewBo("approved", byKind(files, "contact_sheet").sha256(),
            byKind(files, "mp4").sha256(), null);
        return confirmAs(workId, versionId, bo, actor, true);
    }

    /**
     * 自动发布只认落盘的审稿 PASS。没有这条记录就是没审过：历史版本、审稿异常、
     * 只调过审稿但没写回，一律不允许自动发布。fail closed 而不是 fail open，
     * 因为没有记录就无法证明审稿发生过。
     */
    private void requireEditorialPass(HeronVideoVersion version) {
        checkEditorialPass(version.getEditorialReview());
    }

    /**
     * 自动发布只认落盘的审稿 PASS。没有这条记录就是没审过：历史版本、审稿异常、
     * 只调过审稿但没写回，一律不允许自动发布。fail closed 而不是 fail open，
     * 因为没有记录就无法证明审稿发生过。
     */
    static void checkEditorialPass(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new HeronBusinessException(409, "review_required", "该版本没有 AI 审稿记录，不能自动发布");
        }
        JsonNode record;
        try {
            // 用本地 mapper 而不是 JsonUtils：门禁要在纯单测里可验，不能依赖 Spring 上下文
            record = tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "review_invalid", "AI 审稿记录无法解析，不能自动发布");
        }
        if (record == null || !record.isObject()) {
            throw new HeronBusinessException(409, "review_invalid", "AI 审稿记录结构不合法，不能自动发布");
        }
        // 只判非空会被一个 {} 绕过，必须逐项校验
        String result = record.path("result").asString("");
        if (!"PASS".equals(result)) {
            throw new HeronBusinessException(409, "review_not_passed", "AI 审稿结论为 " + result + "，不能自动发布");
        }
        // 缺失不算「没问题」：审稿器每次都写 issues（哪怕是空数组），
        // 字段不见说明这条记录不是我们的审稿器产的，不能拿它当 PASS
        JsonNode issues = record.path("issues");
        if (!issues.isArray()) {
            throw new HeronBusinessException(409, "review_invalid", "AI 审稿记录的问题清单不合法");
        }
        for (JsonNode issue : issues) {
            String severity = issue.path("severity").asString("").strip();
            // 白名单之外的级别不能默认当低风险：拼错的 BLOCKER 也必须拦住
            if (!REVIEW_SEVERITIES.contains(severity)) {
                throw new HeronBusinessException(409, "review_invalid",
                    "AI 审稿记录含未知严重级别：" + (severity.isBlank() ? "（空）" : severity));
            }
            if (!"MINOR".equals(severity)) {
                throw new HeronBusinessException(409, "review_not_passed",
                    "AI 审稿仍带 " + severity + "，不能自动发布");
            }
        }
        // 版本最后判：先给出「明确阻断」的原因，再判记录本身是否可信。
        // 没有版本号的是旧契约记录，放行等于承认一条来路不明的 PASS。
        String schema = record.path("schemaVersion").asString("").strip();
        if (!REVIEW_SCHEMAS.contains(schema)) {
            throw new HeronBusinessException(409, "review_legacy",
                "AI 审稿记录早于当前契约（schemaVersion=" + (schema.isBlank() ? "缺失" : schema) + "），不能自动发布");
        }
    }

    private NewsVideoVersionVo confirmAs(long workId, long versionId, NewsReviewBo bo, long actor, boolean system) {        NewsVideoVersionVo reviewed = reviewAs(workId, versionId, bo, actor, system);
        if (bo == null || !"approved".equals(bo.decision())) {
            return reviewed;
        }
        HeronVideoVersion version = system ? lockVersionAny(workId, versionId) : lockVersion(workId, versionId, "publish");
        // 已发布就回放既有事实：重复点击不能因为沙箱回执时间变了而报冲突
        if (version.getPublishAt() != null) {
            return versionView(version);
        }
        HeronNewsWork work = workMapper.selectById(version.getWorkId());
        PublishReceipt receipt = publishAdapter.publish(new PublishRequest(version.getTaskId(), versionId,
            work == null ? null : work.getTitle(), version.getReviewVideoSha(), now(),
            artifacts.localPath(byKind(readyFiles(version), "mp4"))));
        return applyReceipt(workId, versionId, receipt.workUrl(), receipt.account(), receipt.publishedAt(),
            version.getReviewVideoSha(), receipt.source(), actor, system);
    }

    /** 写发布事实。人工与沙箱只认各自的前缀，命令行出口如实留空链接——两边都不许冒充对方。 */
    private NewsVideoVersionVo applyReceipt(long workId, long versionId, String workUrl, String account,
                                            LocalDateTime publishedAt, String videoSha256, String source,
                                            long actor, boolean system) {
        String url = workUrl == null || workUrl.isBlank() ? null : workUrl.strip();
        HeronVideoVersion version = system ? lockVersionAny(workId, versionId) : lockVersion(workId, versionId, "publish");
        boolean urlOk = switch (source) {
            case SandboxPublishAdapter.SOURCE -> url != null && url.startsWith(SandboxPublishAdapter.URL_PREFIX);
            // sau 不回传作品链接，回执只能空着；链接由人在后台复制后补
            case DouyinCliPublishAdapter.SOURCE -> url == null;
            default -> douyinUrl(url);
        };
        if (!urlOk || publishedAt == null || !sha(videoSha256)) throw invalid("作品链接、发布时间或成片指纹不合法");
        text(account, 120, "账号标识");
        // datetime(3) 只留毫秒；先截断，回执重放才能与已存事实逐位相等
        LocalDateTime published = publishedAt.truncatedTo(ChronoUnit.MILLIS);
        if (published.isAfter(now())) throw conflict("publish_time", "发布时间不能晚于当前时间");
        List<ProductionArtifactVo> files = readyFiles(version);
        ProductionArtifactVo video = byKind(files, "mp4"), contact = byKind(files, "contact_sheet");
        if (!"approved".equals(version.getReviewStatus())
            || !Objects.equals(version.getReviewVideoSha(), video.sha256())
            || !Objects.equals(version.getReviewContactSha(), contact.sha256())) throw conflict("publish_unapproved", "需审核通过且确认指纹仍与成片一致");
        if (!video.sha256().equals(videoSha256)) throw conflict("publish_hash", "回执指纹与该版本成片不一致");
        HeronNewsWork work = workMapper.selectById(version.getWorkId());
        HeronNewsCandidate candidate = candidateMapper.selectById(work.getCandidateId());
        if (candidate == null) throw new HeronAccessException();
        if (version.getPublishAt() != null) {
            if (Objects.equals(version.getPublishUrl(), url)
                && Objects.equals(version.getPublishAccount(), account.strip())
                && Objects.equals(version.getPublishAt(), published)
                && Objects.equals(version.getPublishVideoSha(), videoSha256)) return versionView(version);
            throw conflict("publish_conflict", "该版本已回填发布回执");
        }
        // 一个候选只有一条已发布事实；换版本重渲不能覆盖它，只能人工先处置
        if (candidate.getPublishedAt() != null) throw conflict("publish_conflict", "该候选已有发布记录");
        if (versionMapper.lambda().set(HeronVideoVersion::getPublishSource, source).set(HeronVideoVersion::getPublishUrl, url)
            .set(HeronVideoVersion::getPublishAccount, account.strip()).set(HeronVideoVersion::getPublishAt, published)
            .set(HeronVideoVersion::getPublishBy, actor).set(HeronVideoVersion::getPublishVideoSha, videoSha256)
            .set(HeronVideoVersion::getUpdateBy, actor).set(HeronVideoVersion::getUpdateTime, now())
            .eq(HeronVideoVersion::getVersionId, versionId).eq(HeronVideoVersion::getReviewStatus, "approved")
            .isNull(HeronVideoVersion::getPublishAt).isNull(HeronVideoVersion::getCleanupStartedAt).updateCount() != 1) {
            throw conflict("publish_conflict", "发布回执已变化");
        }
        if (candidateMapper.lambda().set(HeronNewsCandidate::getPublishedAt, published)
            .set(HeronNewsCandidate::getUpdateBy, actor).set(HeronNewsCandidate::getUpdateTime, now())
            .eq(HeronNewsCandidate::getCandidateId, work.getCandidateId()).isNull(HeronNewsCandidate::getPublishedAt).updateCount() != 1) {
            throw conflict("publish_conflict", "候选发布状态已变化");
        }
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
                        domain.safePath(file);
                        if (artifacts.exists(file)) artifacts.removeFile(file);
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

    /** 后台线程没有会话：直接按 id 取工作与版本并加锁，不走对象级权限校验。 */
    private HeronVideoVersion lockVersionAny(long workId, long versionId) {
        HeronNewsWork work = workMapper.selectById(workId);
        if (work == null) throw new HeronAccessException();
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
        domain.validateChecks(version.getChecks(), domain.dataBoard(version));
        List<ProductionArtifactVo> files = parseManifest(version);
        domain.validateManifest(version, task, files, false);
        for (ProductionArtifactVo file : files) {
            if (!artifacts.exists(file)) {
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
            vo.setActiveTask(MapstructUtils.convert(task, ProductionTaskVo.class));
        }
    }

    private NewsVideoVersionVo versionView(HeronVideoVersion version) {
        HeronProductionTask task = taskMapper.selectById(version.getTaskId());
        domain.requireTask(version, task);
        NewsVideoVersionVo vo = MapstructUtils.convert(version, NewsVideoVersionVo.class);
        vo.setTaskStatus(task.getStatus());
        vo.setTaskErrorCode(task.getErrorCode());
        vo.setTaskErrorReason(task.getErrorReason());
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
    /** 只认 douyin.com 的 https 作品链接，拒绝任何可伪装成它的形状。 */
    private boolean douyinUrl(String value) {
        if (value == null || value.indexOf('\0') >= 0 || value.length() > 2048) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                && host != null && (host.equals("douyin.com") || host.endsWith(".douyin.com"))
                && uri.getPath() != null && uri.getPath().length() > 1;
        } catch (URISyntaxException e) {
            return false;
        }
    }
    private void text(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) throw invalid(name + "为空或超长");
    }
    private HeronBusinessException invalid(String message) { return new HeronBusinessException(400, "news_invalid_input", message); }
    private HeronBusinessException conflict(String code, String message) { return new HeronBusinessException(409, "news_" + code, message); }

}
