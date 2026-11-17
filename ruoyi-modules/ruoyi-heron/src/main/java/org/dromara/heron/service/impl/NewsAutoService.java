package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.HeronAutoSlot;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.vo.NewsWorkVo;
import org.dromara.heron.mapper.HeronAutoSlotMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsSourceMapper;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.AutoPauseStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 自动产出：每个 cron 槽位最多产出一条，槽位幂等键是日期+时刻。
 * 人工闸在这里被关掉，换来的约束是：源级开关、24 小时时效、热度优先、同源不连续、空池跳过、可下架。
 */
@Service
@Slf4j
public class NewsAutoService {
    private static final int POOL_LIMIT = 50;
    private static final int SWEEP_LIMIT = 10;
    private static final int DATASET_POOL_LIMIT = 20;
    /** 审稿没过时换选题的次数上限：加上首次，同一槽位最多试 3 个选题。 */
    private static final int MAX_REVIEW_RETRIES = 2;
    /**
     * 连续这么多个选题都「核不了」就停手。
     *
     * <p>核不了是证据片段的质量问题——换选题救不了，继续换只是多烧几轮模型调用。
     */
    private static final int UNVERIFIABLE_STREAK_LIMIT = 2;
    /** 研究候选单独取：它们的 heat_score 恒为 1，混进按热度排序的 LIMIT 里几乎永不上榜。 */
    private static final int RESEARCH_POOL_LIMIT = 10;
    /** RuoYi 的开关约定：'0' 是允许。 */
    private static final String AUTO_ALLOWED = "0";
    private static final String PROBE_OK = "ok";
    private static final int RECENT_LIMIT = 10;
    /** 数据机会:只认 24 小时内算出来的，进池取前 10 条。 */
    private static final int OPPORTUNITY_HOURS = 24;
    private static final int OPPORTUNITY_LIMIT = 10;
    /** 本地 mapper：决策留痕要在纯单测里可验，不能依赖 Spring 上下文里的 JsonUtils。 */
    private static final tools.jackson.databind.json.JsonMapper JSON =
        tools.jackson.databind.json.JsonMapper.builder().build();

    private final HeronNewsProperties news;
    private final HeronNewsSourceMapper sourceMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final HeronAutoSlotMapper slotMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final HeronDataSourceMapper dataSourceMapper;
    private final NewsWorkServiceImpl newsWork;
    private final AutoPauseStore pauseStore;
    private final NewsCandidateScreener screener;
    private final EditorialDecisionService editorial;
    private final NewsProductionDomain domain;
    private final EditorialLineEditor lineEditor;
    private final DatasetRouter datasetRouter;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public NewsAutoService(HeronNewsProperties news, HeronNewsSourceMapper sourceMapper,
                           HeronNewsCandidateMapper candidateMapper, HeronNewsWorkMapper workMapper,
                           HeronVideoVersionMapper versionMapper, HeronAutoSlotMapper slotMapper,
                           HeronDataOpportunityMapper opportunityMapper,
                           HeronDataSourceMapper dataSourceMapper,
                           NewsWorkServiceImpl newsWork, AutoPauseStore pauseStore, NewsCandidateScreener screener,
                           EditorialDecisionService editorial, NewsProductionDomain domain, EditorialLineEditor lineEditor,
                           DatasetRouter datasetRouter) {
        this(news, sourceMapper, candidateMapper, workMapper, versionMapper, slotMapper, opportunityMapper,
            dataSourceMapper, newsWork,
            pauseStore, screener, editorial, domain, lineEditor, datasetRouter, Clock.systemUTC());
    }

    public NewsAutoService(HeronNewsProperties news, HeronNewsSourceMapper sourceMapper,
                           HeronNewsCandidateMapper candidateMapper, HeronNewsWorkMapper workMapper,
                           HeronVideoVersionMapper versionMapper, HeronAutoSlotMapper slotMapper,
                           HeronDataOpportunityMapper opportunityMapper,
                           HeronDataSourceMapper dataSourceMapper,
                           NewsWorkServiceImpl newsWork, AutoPauseStore pauseStore, NewsCandidateScreener screener,
                           EditorialDecisionService editorial, NewsProductionDomain domain, EditorialLineEditor lineEditor,
                           DatasetRouter datasetRouter, Clock clock) {
        this.news = news;
        this.sourceMapper = sourceMapper;
        this.candidateMapper = candidateMapper;
        this.workMapper = workMapper;
        this.versionMapper = versionMapper;
        this.slotMapper = slotMapper;
        this.opportunityMapper = opportunityMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.newsWork = newsWork;
        this.pauseStore = pauseStore;
        this.screener = screener;
        this.editorial = editorial;
        this.domain = domain;
        this.lineEditor = lineEditor;
        this.datasetRouter = datasetRouter;
        this.clock = clock;
    }

    public boolean paused() {
        return pauseStore.paused();
    }

    public void pause(boolean paused) {
        pauseStore.pause(paused);
    }

    /** 回读今天某个槽位的结果；手动触发后用它给调用方一个确定的回答。 */
    public HeronAutoSlot slot(String slotTime) {
        return slotMapper.selectOne(QueryBuilder.lambda(HeronAutoSlot.class)
            .eq(HeronAutoSlot::getSlotDate, LocalDate.now(clock))
            .eq(HeronAutoSlot::getSlotTime, slotTime).last("LIMIT 1").build());
    }

    /** 槽位触发：最多产出一条。所有提前退出都会在槽位表留一行原因，不静默。 */
    public void tick(String slotTime) {
        tick(slotTime, false);
    }

    /**
     * @param manual 人工触发：人按下的动作不受「自动开关」与「暂停」限制，
     *               这两个开关管的是无人值守，不是人的明确意图。
     */
    public void tick(String slotTime, boolean manual) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        HeronAutoSlot slot = new HeronAutoSlot();
        slot.setSlotDate(today);
        slot.setSlotTime(slotTime);
        slot.setStatus("running");
        slot.setCreateTime(now);
        slot.setUpdateTime(now);
        try {
            slotMapper.insert(slot);
        } catch (DuplicateKeyException e) {
            // 同一槽位重复触发：以第一次为准
            return;
        }
        if (!manual && !news.isAutoEnabled()) {
            finish(slot, "skipped", "auto_disabled", null);
            return;
        }
        if (!manual && paused()) {
            finish(slot, "skipped", "paused", null);
            return;
        }
        // 先建池，再由总编辑定线，最后线内编辑从同一个池子里定素材。
        // 顺序不能倒：反过来就是「先选出候选，再决定这条候选该走哪条线」。
        EditorialDecisionService.Pools pools;
        EditorialDecisionService.Decision decision;
        EditorialCandidatePool pool;
        try {
            BuiltPool built = buildPool(now);
            pool = built.pool();
            if (pool.isEmpty()) {
                finish(slot, "skipped", built.emptyReason(), null);
                return;
            }
            pools = pools(pool);
            decision = editorial.decide(pools);
        } catch (RuntimeException e) {
            // 决策失败不产出：宁可空槽，也不按默认线硬发
            finish(slot, "failed", "editorial_decision: " + e.getMessage(), null);
            return;
        }
        if (!decision.publish()) {
            // NO_PUBLISH 也要留痕：空槽看起来像故障，必须有理由
            markDecision(slot, decision, pools, null);
            finish(slot, "skipped", "no_publish: " + decision.reason(), null);
            return;
        }
        // 审稿没过时换选题自救。只对「证据问题」重试：契约、取数、渲染这类失败
        // 换选题避不开，只会多烧一轮。
        EditorialCandidatePool current = pool;
        Set<Long> triedCandidates = new HashSet<>();
        Set<String> triedSources = new HashSet<>();
        int unverifiableStreak = 0;
        String lastReason = null;
        for (int attempt = 0; attempt <= MAX_REVIEW_RETRIES; attempt++) {
            Optional<EditorialChoice> choice;
            try {
                // 传池子而不是摘要：线内编辑必须从总编辑看过的那批数据里挑。
                // 重试时清掉 focus——总编辑锁定的就是刚失败的那条，留着它只会原地打转。
                choice = lineEditor.select(decision.selectedLine(), current, decision.editorialIntent(),
                    attempt == 0 ? decision.focusCandidateId() : null,
                    attempt == 0 ? decision.focusOpportunityId() : null);
            } catch (RuntimeException e) {
                finish(slot, "failed", "line_editor: " + e.getMessage(), null);
                return;
            }
            if (choice.isEmpty()) {
                if (attempt == 0) {
                    // 线内编辑找不到可用素材：不硬做，也不回问总编辑；原判线照旧留痕
                    markDecision(slot, decision, pools, null);
                    finish(slot, "skipped", "no_fit: " + decision.selectedLine(), null);
                } else {
                    finish(slot, "failed", "retry_no_fit: 换选题后池子里已无可用素材", null);
                }
                return;
            }
            EditorialChoice picked = choice.get();
            // 槽位记的是实际执行线：早写会把「总编辑想走数据看板、实际走了解说」写反
            markDecision(slot, decision, pools, picked);
            // 候选在最终选择确定后才记：之前记的是「预选候选」，会与实际生产的那条不符
            slotMapper.lambda().set(HeronAutoSlot::getCandidateId, picked.selection().candidateId())
                .eq(HeronAutoSlot::getSlotDate, slot.getSlotDate())
                .eq(HeronAutoSlot::getSlotTime, slot.getSlotTime()).update();
            NewsWorkVo work = null;
            try {
                work = newsWork.claimForAuto(picked.selection(), news.getAutoActorId());
                org.dromara.heron.domain.vo.ProductionReceiptVo receipt = newsWork.generateForAuto(
                    work.getWorkId(), "auto:" + today + ":" + slotTime, decision, picked.selection());
                markOpportunityUsed(picked.selection(), receipt);
                finish(slot, "produced", picked.finalLine() + "：" + decision.editorialIntent(), work.getWorkId());
                return;
            } catch (RuntimeException e) {
                log.warn("自动产出槽位失败 slot={} attempt={} {}: {}",
                    slotTime, attempt, e.getClass().getSimpleName(), e.getMessage());
                // 认领已经落库，提交失败必须把候选放回池子：不释放就再也不会被选中，且不报错
                if (work != null) {
                    try {
                        newsWork.abandonAuto(work.getWorkId());
                    } catch (RuntimeException compensation) {
                        log.warn("自动产出补偿失败 workId={}: {}", work.getWorkId(), compensation.getMessage());
                    }
                }
                if (!isEvidenceReviewFailure(e)) {
                    finish(slot, "failed", e.getMessage(), null);
                    return;
                }
                lastReason = e.getMessage();
                unverifiableStreak = "editorial_review_unverifiable".equals(businessCode(e))
                    ? unverifiableStreak + 1 : 0;
                if (unverifiableStreak >= UNVERIFIABLE_STREAK_LIMIT) {
                    // 停手的理由要说清楚：不是选题不好，是证据片段本身不够
                    finish(slot, "failed", "evidence_fragment_poor: 连续 " + unverifiableStreak
                        + " 个选题都核不住，换选题救不了；" + lastReason, null);
                    return;
                }
                if (picked.selection().candidateId() != null) {
                    triedCandidates.add(picked.selection().candidateId());
                }
                if (picked.selection().dataSourceKey() != null) {
                    triedSources.add(picked.selection().dataSourceKey());
                }
                current = current.excluding(triedCandidates, triedSources);
            }
        }
        finish(slot, "failed", "retry_exhausted: 已试 " + (MAX_REVIEW_RETRIES + 1)
            + " 个选题仍未过审；" + lastReason, null);
    }

    /** 审稿没过且是证据问题：换选题可能换得动，其余失败换不掉。 */
    static boolean isEvidenceReviewFailure(RuntimeException e) {
        String code = businessCode(e);
        return "editorial_review_unverifiable".equals(code) || "editorial_review_unsupported".equals(code);
    }

    static String businessCode(RuntimeException e) {
        return e instanceof HeronBusinessException business ? business.getCode() : null;
    }

    /**
     * 建一次候选池，之后总编辑与线内编辑都只从这里取。
     *
     * <p>来源白名单、24 小时时效、数据源的探测状态与 7 天去重全部在这里一次性生效。
     * 之前线内编辑收到摘要后又自己查了一次库，那些边界条件在那次重查里全丢了——
     * 总编辑看到的池子和真正被挑的池子是两批数据。
     */
    private EditorialCandidatePool candidatePool(LocalDateTime now) {
        return buildPool(now).pool();
    }

    /**
     * @param emptyReason 空池的原因；槽位 detail 用它，否则排查时看到 no_candidate 会以为
     *                    是「没有素材」，实际可能是「没有配新闻源」或「今天没算出机会」
     */
    record BuiltPool(EditorialCandidatePool pool, String emptyReason) { }

    BuiltPool buildPool(LocalDateTime now) {
        List<HeronNewsSource> sources = autoSources();
        if (sources.isEmpty()) {
            log.warn("自动源白名单为空：本期没有新闻候选");
        }
        Set<String> autoHosts = hosts(sources);
        List<HeronNewsCandidate> allowed = new ArrayList<>();
        if (!sources.isEmpty()) {
            List<HeronNewsCandidate> candidates = candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
                .isNull(HeronNewsCandidate::getPublishedAt)
                .isNull(HeronNewsCandidate::getClaimedBy)
                .isNull(HeronNewsCandidate::getActiveTaskId)
                .ge(HeronNewsCandidate::getLastSeenAt, now.minusHours(24))
                .orderByDesc(HeronNewsCandidate::getHeatScore)
                .orderByAsc(HeronNewsCandidate::getBestRank)
                .last("LIMIT " + POOL_LIMIT).build());
            for (HeronNewsCandidate candidate : candidates) {
                // 研究来源不进自动池：它没有「源」可以逐源灰度与下架，
                // 放进来等于给 Agent 衍生的选题开一条无准入的直发通道。
                // 研究候选仍在人工选题列表里，走总编辑那条路。
                if (HeronNewsCandidate.SOURCE_RESEARCH.equals(candidate.getSourceKind())) {
                    continue;
                }
                String host = firstHost(candidate.getEvidence());
                // 没开自动的来源不进池：这是「可下架」的边界，不是权重
                if (host != null && autoHosts.contains(host)) {
                    allowed.add(candidate);
                }
            }
        }
        List<HeronDataOpportunity> opportunities = opportunities(now);
        // 研究来源单独取并合并：它走数据集源的逐源准入，和新闻源的 host 白名单是两套闸
        allowed.addAll(researchCandidates(now));
        if (allowed.isEmpty() && opportunities.isEmpty()) {
            // 新闻和数据机会都空才是真的没得做；只有数据机会时，数据看板照样能出
            String reason = sources.isEmpty()
                ? "no_news_source_and_no_opportunity"
                : "no_fresh_candidate_and_no_opportunity";
            return new BuiltPool(EditorialCandidatePool.empty(), reason);
        }
        return new BuiltPool(EditorialCandidatePool.of(allowed, EditorialLineEditor::hasBody,
            datasets(now), sources, recentWorks(), opportunities), null);
    }

    /**
     * 研究候选进自动池：只认「来源数据源被显式开启自动且探测 ok」的那些。
     *
     * <p>热度排序对研究候选没意义（它恒为 1），所以单独取，不与热榜抢同一个 LIMIT。
     * 24 小时窗口照旧：研究候选也是当天要做的选题，隔天再自动发布已经不新鲜了；
     * 人工选题列表没有这个限制，没跑掉的选题仍在那边。
     */
    private List<HeronNewsCandidate> researchCandidates(LocalDateTime now) {
        Set<String> autoKeys = autoResearchSources();
        if (autoKeys.isEmpty()) {
            return List.of();
        }
        try {
            return candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
                .eq(HeronNewsCandidate::getSourceKind, HeronNewsCandidate.SOURCE_RESEARCH)
                .isNull(HeronNewsCandidate::getPublishedAt)
                .isNull(HeronNewsCandidate::getClaimedBy)
                .isNull(HeronNewsCandidate::getActiveTaskId)
                .ge(HeronNewsCandidate::getLastSeenAt, now.minusHours(24))
                .in(HeronNewsCandidate::getSourceKey, autoKeys)
                .orderByDesc(HeronNewsCandidate::getLastSeenAt)
                .last("LIMIT " + RESEARCH_POOL_LIMIT).build());
        } catch (RuntimeException e) {
            log.warn("研究候选不可用，本期自动池不含研究来源: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 哪些数据源此刻允许自动发布研究候选。
     *
     * <p>三个条件缺一不可，就是「逐源开启 + 灰度准入 + 可下架」的落点：
     * 源本身启用、显式打开了自动开关（默认是关的）、探测为 ok。
     */
    static Set<String> autoResearchSources(List<HeronDataSource> enabled) {
        Set<String> keys = new HashSet<>();
        for (HeronDataSource source : enabled) {
            if (AUTO_ALLOWED.equals(source.getAutoEnabled()) && PROBE_OK.equals(source.getProbeStatus())) {
                keys.add(source.getSourceKey());
            }
        }
        return keys;
    }

    private Set<String> autoResearchSources() {
        try {
            return autoResearchSources(dataSourceMapper.enabledSources());
        } catch (RuntimeException e) {
            log.warn("数据源自动准入不可用，本期不含研究来源: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * 今天过线且未淘汰的数据机会，分数高的在前。
     * 读失败按「没有机会」处理：不能让一个附属查询把整个槽位打挂。
     */
    private List<HeronDataOpportunity> opportunities(LocalDateTime now) {
        try {
            return opportunityMapper.selectList(QueryBuilder.lambda(HeronDataOpportunity.class)
                .eq(HeronDataOpportunity::getStatus, DataOpportunityService.STATUS_NEW)
                .ge(HeronDataOpportunity::getObservedAt, now.minusHours(OPPORTUNITY_HOURS))
                .orderByDesc(HeronDataOpportunity::getScore)
                .last("LIMIT " + OPPORTUNITY_LIMIT).build());
        } catch (RuntimeException e) {
            log.warn("读取数据机会失败，本期按没有机会处理: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 机会做成片子之后置 used：不置的话，同一天的下一个槽位会把它再挑一次，产出同一条内容。
     * 记账失败不回滚已经产出的片子——片子是对的，账错了不能倒过来。
     */
    private void markOpportunityUsed(org.dromara.heron.domain.EditorialSelection selection,
                                     org.dromara.heron.domain.vo.ProductionReceiptVo receipt) {
        if (selection.opportunityId() == null || receipt == null || receipt.versionId() <= 0) {
            return;
        }
        try {
            opportunityMapper.markUsed(selection.opportunityId(), receipt.versionId(), LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            log.warn("标记数据机会已用失败 opportunityId={}: {}", selection.opportunityId(), e.getMessage());
        }
    }

    /** 数据源候选：启用、探测 ok、7 天内没用过。取不到就当空，不让整个槽位失败。 */
    private List<HeronDataSource> datasets(LocalDateTime now) {
        try {
            return datasetRouter.eligible(now).stream()
                .sorted(java.util.Comparator.comparing(HeronDataSource::getSourceKey))
                .limit(DATASET_POOL_LIMIT)
                .toList();
        } catch (RuntimeException e) {
            log.warn("数据源候选不可用，本期数据看板线为空: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 总编辑只看概况：带 ID 的候选摘要 + 冻结的数据源候选。
     * 纯投影——方法体内不得出现任何 mapper 调用，查库都属于建池。
     */
    static EditorialDecisionService.Pools pools(EditorialCandidatePool pool) {
        List<EditorialDecisionService.CandidateRef> hot = pool.hot().stream()
            .map(item -> new EditorialDecisionService.CandidateRef(item.getCandidateId(), item.getTitle())).toList();
        List<EditorialDecisionService.CandidateRef> articles = pool.articles().stream()
            .map(item -> new EditorialDecisionService.CandidateRef(item.getCandidateId(), item.getTitle())).toList();
        List<EditorialDecisionService.DatasetRef> datasets = pool.datasets().stream()
            .map(source -> new EditorialDecisionService.DatasetRef(source.getSourceKey(), datasetLabel(source))).toList();
        // 机会的 label 必须带分数与变化：只给标题，总编辑没有依据判断值不值得做
        List<EditorialDecisionService.OpportunityRef> opportunities = pool.opportunities().stream()
            .map(item -> new EditorialDecisionService.OpportunityRef(item.getOpportunityId(), opportunityLabel(item))).toList();
        List<String> recent = pool.recent().stream().map(HeronNewsWork::getTitle)
            .filter(title -> title != null && !title.isBlank()).toList();
        return new EditorialDecisionService.Pools(pool.poolId(), pool.poolHash(), hot, articles, datasets,
            opportunities, recent);
    }

    static String opportunityLabel(HeronDataOpportunity item) {
        String score = item.getScore() == null ? "" : "，分 " + item.getScore().stripTrailingZeros().toPlainString();
        return item.getHeadline() + score;
    }

    private static String datasetLabel(HeronDataSource source) {
        return source.getName() + "（" + source.getCadence() + "，" + source.getShapes() + "）";
    }

    private List<HeronNewsWork> recentWorks() {
        return workMapper.selectList(QueryBuilder.lambda(HeronNewsWork.class)
            .orderByDesc(HeronNewsWork::getUpdateTime).last("LIMIT " + RECENT_LIMIT).build());
    }

    /** 自动模式稿件生产完成后自动确认发布；人工模式稿件不在此扫描范围内。 */
    public void confirmPending() {
        if (!news.isAutoEnabled() || !news.isAutoConfirmEnabled() || paused()) {
            return;
        }
        List<HeronNewsWork> works = workMapper.selectList(QueryBuilder.lambda(HeronNewsWork.class)
            .eq(HeronNewsWork::getAutoPublish, "0")
            .isNotNull(HeronNewsWork::getCurrentVersionId)
            .orderByAsc(HeronNewsWork::getUpdateTime).last("LIMIT " + SWEEP_LIMIT).build());
        for (HeronNewsWork work : works) {
            HeronVideoVersion version = versionMapper.selectById(work.getCurrentVersionId());
            if (version == null || !"pending".equals(version.getReviewStatus()) || version.getPublishAt() != null) {
                continue;
            }
            try {
                newsWork.confirmForAuto(work.getWorkId(), version.getVersionId(), news.getAutoActorId());
            } catch (RuntimeException e) {
                // 留在待确认状态，工作台可见；不重试、不改稿
                log.warn("自动确认失败 workId={} versionId={}: {}", work.getWorkId(), version.getVersionId(), e.getMessage());
            }
        }
    }


    private List<HeronNewsSource> autoSources() {
        return sourceMapper.selectList(QueryBuilder.lambda(HeronNewsSource.class)
            .eq(HeronNewsSource::getEnabled, "0").eq(HeronNewsSource::getAuto, "0").build());
    }

    private static Set<String> hosts(List<HeronNewsSource> sources) {
        Set<String> hosts = new HashSet<>();
        for (HeronNewsSource source : sources) {
            try {
                hosts.add(URI.create(source.getUrl()).getHost());
            } catch (RuntimeException e) {
                // 来源 URL 在保存时已校验；这里读不出主机名就当它不参与自动
                log.warn("来源 {} 的 URL 无法解析主机名", source.getSourceId());
            }
        }
        hosts.remove(null);
        return hosts;
    }


    private static String firstHost(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonUtils.getJsonMapper().readTree(evidence);
            for (JsonNode item : node.path("items")) {
                String url = item.path("sourceUrl").asString("");
                if (!url.isBlank()) {
                    return URI.create(url).getHost();
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * 决策结构化落库。detail 只是一段给人看的文本，编辑策划页要能回答「它看到了什么、为什么这么选」。
     *
     * <p>selected_line 写的是**实际执行线**：早写会把「总编辑想走数据看板、实际走了解说」记反。
     * 总编辑原判线与回退原因放进 pool_json.decision，两者都留才谈得上追溯。
     */
    private void markDecision(HeronAutoSlot slot, EditorialDecisionService.Decision decision,
                              EditorialDecisionService.Pools pools, EditorialChoice choice) {
        slotMapper.lambda()
            .set(HeronAutoSlot::getSelectedLine, executedLine(decision, choice))
            .set(HeronAutoSlot::getEditorialIntent, clip(decision.editorialIntent(), 500))
            .set(HeronAutoSlot::getDecisionReason, clip(decision.reason(), 500))
            .set(HeronAutoSlot::getPoolJson, decisionTrail(pools, decision, choice))
            .set(HeronAutoSlot::getUpdateTime, LocalDateTime.now(clock))
            .eq(HeronAutoSlot::getSlotDate, slot.getSlotDate())
            .eq(HeronAutoSlot::getSlotTime, slot.getSlotTime())
            .update();
    }

    static String executedLine(EditorialDecisionService.Decision decision, EditorialChoice choice) {
        if (!decision.publish()) {
            return EditorialDecisionService.NO_PUBLISH;
        }
        return choice != null ? choice.finalLine() : decision.selectedLine();
    }

    /**
     * pool_json 的四个原键一个字都不能改：编辑策划页按 hotHeadlines / articleCandidates /
     * dataSources / recentPublications 直接解析（heron-ui types.ts:58、index.vue:219）。
     * 新键一律平级追加，嵌套会白屏。
     */
    static String decisionTrail(EditorialDecisionService.Pools pools,
                                        EditorialDecisionService.Decision decision, EditorialChoice choice) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode hot = root.putArray("hotHeadlines");
        pools.hot().forEach(ref -> hot.add(ref.label()));
        ArrayNode articles = root.putArray("articleCandidates");
        pools.articles().forEach(ref -> articles.add(ref.label()));
        ArrayNode dataSources = root.putArray("dataSources");
        pools.datasets().forEach(ref -> dataSources.add(ref.label()));
        // 新键平级追加：前端只认上面四个原键，多出来的键它不解析也不会白屏
        ArrayNode opportunities = root.putArray("opportunities");
        pools.opportunities().forEach(ref -> opportunities.add(ref.label()));
        ArrayNode recent = root.putArray("recentPublications");
        pools.recentPublications().forEach(recent::add);
        root.put("poolId", pools.poolId());
        root.put("poolHash", pools.poolHash());
        ObjectNode trail = root.putObject("decision");
        trail.put("originalLine", decision.publish() ? decision.selectedLine() : EditorialDecisionService.NO_PUBLISH);
        trail.put("finalLine", executedLine(decision, choice));
        if (decision.focusOpportunityId() != null) {
            trail.put("focusOpportunityId", decision.focusOpportunityId());
        }
        trail.put("fallbackApplied", choice != null && choice.fallbackApplied());
        if (choice != null && choice.fallbackReason() != null) {
            trail.put("fallbackReason", choice.fallbackReason());
        } else {
            trail.putNull("fallbackReason");
        }
        return JSON.writeValueAsString(root);
    }

    private static String clip(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private void finish(HeronAutoSlot slot, String status, String detail, Long workId) {
        slotMapper.lambda()
            .set(HeronAutoSlot::getStatus, status)
            .set(HeronAutoSlot::getDetail, clip(detail, 2000))
            .set(HeronAutoSlot::getWorkId, workId)
            .set(HeronAutoSlot::getUpdateTime, LocalDateTime.now(clock))
            .eq(HeronAutoSlot::getSlotDate, slot.getSlotDate())
            .eq(HeronAutoSlot::getSlotTime, slot.getSlotTime())
            .update();
    }
}
