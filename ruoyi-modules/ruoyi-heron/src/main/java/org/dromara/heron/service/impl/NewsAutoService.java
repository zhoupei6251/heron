package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronAutoSlot;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.vo.NewsWorkVo;
import org.dromara.heron.mapper.HeronAutoSlotMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronDatasetUsageMapper;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsSourceMapper;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.AutoPauseStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

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
    private static final int RECENT_LIMIT = 10;

    private final HeronNewsProperties news;
    private final HeronNewsSourceMapper sourceMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final HeronAutoSlotMapper slotMapper;
    private final NewsWorkServiceImpl newsWork;
    private final AutoPauseStore pauseStore;
    private final NewsCandidateScreener screener;
    private final EditorialDecisionService editorial;
    private final NewsProductionDomain domain;
    private final EditorialLineEditor lineEditor;
    private final HeronDataSourceMapper dataSourceMapper;
    private final HeronDatasetUsageMapper usageMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public NewsAutoService(HeronNewsProperties news, HeronNewsSourceMapper sourceMapper,
                           HeronNewsCandidateMapper candidateMapper, HeronNewsWorkMapper workMapper,
                           HeronVideoVersionMapper versionMapper, HeronAutoSlotMapper slotMapper,
                           NewsWorkServiceImpl newsWork, AutoPauseStore pauseStore, NewsCandidateScreener screener,
                           EditorialDecisionService editorial, NewsProductionDomain domain, EditorialLineEditor lineEditor,
                           HeronDataSourceMapper dataSourceMapper, HeronDatasetUsageMapper usageMapper) {
        this(news, sourceMapper, candidateMapper, workMapper, versionMapper, slotMapper, newsWork, pauseStore,
            screener, editorial, domain, lineEditor, dataSourceMapper, usageMapper, Clock.systemUTC());
    }

    public NewsAutoService(HeronNewsProperties news, HeronNewsSourceMapper sourceMapper,
                           HeronNewsCandidateMapper candidateMapper, HeronNewsWorkMapper workMapper,
                           HeronVideoVersionMapper versionMapper, HeronAutoSlotMapper slotMapper,
                           NewsWorkServiceImpl newsWork, AutoPauseStore pauseStore, NewsCandidateScreener screener,
                           EditorialDecisionService editorial, NewsProductionDomain domain, EditorialLineEditor lineEditor,
                           HeronDataSourceMapper dataSourceMapper, HeronDatasetUsageMapper usageMapper,
                           Clock clock) {
        this.news = news;
        this.sourceMapper = sourceMapper;
        this.candidateMapper = candidateMapper;
        this.workMapper = workMapper;
        this.versionMapper = versionMapper;
        this.slotMapper = slotMapper;
        this.newsWork = newsWork;
        this.pauseStore = pauseStore;
        this.screener = screener;
        this.editorial = editorial;
        this.domain = domain;
        this.lineEditor = lineEditor;
        this.dataSourceMapper = dataSourceMapper;
        this.usageMapper = usageMapper;
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
            pool = candidatePool(now);
            if (pool.isEmpty()) {
                finish(slot, "skipped", "no_candidate", null);
                return;
            }
            pools = pools(pool, now);
            decision = editorial.decide(pools);
        } catch (RuntimeException e) {
            // 决策失败不产出：宁可空槽，也不按默认线硬发
            finish(slot, "failed", "editorial_decision: " + e.getMessage(), null);
            return;
        }
        markDecision(slot, decision, pools);
        if (!decision.publish()) {
            finish(slot, "skipped", "no_publish: " + decision.reason(), null);
            return;
        }
        Optional<EditorialSelection> selection;
        try {
            // 传池子而不是摘要：线内编辑必须从总编辑看过的那批数据里挑
            selection = lineEditor.select(decision.selectedLine(), pool, decision.editorialIntent());
        } catch (RuntimeException e) {
            finish(slot, "failed", "line_editor: " + e.getMessage(), null);
            return;
        }
        if (selection.isEmpty()) {
            // 线内编辑找不到可用素材：不硬做，也不回问总编辑
            finish(slot, "skipped", "no_fit: " + decision.selectedLine(), null);
            return;
        }
        EditorialSelection picked = selection.get();
        // 候选在最终选择确定后才记：之前记的是「预选候选」，会与实际生产的那条不符
        slotMapper.lambda().set(HeronAutoSlot::getCandidateId, picked.candidateId())
            .eq(HeronAutoSlot::getSlotDate, slot.getSlotDate())
            .eq(HeronAutoSlot::getSlotTime, slot.getSlotTime()).update();
        try {
            NewsWorkVo work = newsWork.claimForAuto(picked, news.getAutoActorId());
            newsWork.generateForAuto(work.getWorkId(), "auto:" + today + ":" + slotTime, decision, picked);
            finish(slot, "produced", picked.line() + "：" + decision.editorialIntent(), work.getWorkId());
        } catch (RuntimeException e) {
            // 失败留痕即可；下一槽位会重新挑，不跨槽位补发
            log.warn("自动产出槽位失败 slot={} {}: {}", slotTime, e.getClass().getSimpleName(), e.getMessage());
            finish(slot, "failed", e.getMessage(), null);
        }
    }

    /**
     * 建一次候选池，之后总编辑与线内编辑都只从这里取。
     *
     * <p>来源白名单与 24 小时时效在这里一次性生效。之前线内编辑收到摘要后
     * 又自己查了一次库，那些边界条件在那次重查里全丢了——
     * 总编辑看到的池子和真正被挑的池子是两批数据。
     */
    private EditorialCandidatePool candidatePool(LocalDateTime now) {
        Set<String> autoHosts = autoHosts();
        if (autoHosts.isEmpty()) {
            log.warn("自动源白名单为空，本期不产出");
            return EditorialCandidatePool.empty();
        }
        List<HeronNewsCandidate> candidates = candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
            .isNull(HeronNewsCandidate::getPublishedAt)
            .isNull(HeronNewsCandidate::getClaimedBy)
            .isNull(HeronNewsCandidate::getActiveTaskId)
            .ge(HeronNewsCandidate::getLastSeenAt, now.minusHours(24))
            .orderByDesc(HeronNewsCandidate::getHeatScore)
            .orderByAsc(HeronNewsCandidate::getBestRank)
            .last("LIMIT " + POOL_LIMIT).build());
        List<HeronNewsCandidate> allowed = new ArrayList<>();
        for (HeronNewsCandidate candidate : candidates) {
            String host = firstHost(candidate.getEvidence());
            // 没开自动的来源不进池：这是「可下架」的边界，不是权重
            if (host != null && autoHosts.contains(host)) {
                allowed.add(candidate);
            }
        }
        return EditorialCandidatePool.sort(allowed, EditorialLineEditor::hasBody);
    }

    /** 总编辑只看概况：具体条目留给线内编辑从同一个池子里选。 */
    private EditorialDecisionService.Pools pools(EditorialCandidatePool pool, LocalDateTime now) {
        List<String> hot = pool.hot().stream().map(HeronNewsCandidate::getTitle).toList();
        List<String> articles = pool.articles().stream().map(HeronNewsCandidate::getTitle).toList();
        List<String> sources = dataSourceOptions(now);
        List<String> recent = workMapper.selectList(QueryBuilder.lambda(HeronNewsWork.class)
                .orderByDesc(HeronNewsWork::getUpdateTime).last("LIMIT " + RECENT_LIMIT).build())
            .stream().map(HeronNewsWork::getTitle).filter(title -> title != null && !title.isBlank())
            .toList();
        return new EditorialDecisionService.Pools(hot, articles, sources, recent);
    }

    /** 未启用、探测非 ok、近 7 天用过的源都不进候选。 */
    private List<String> dataSourceOptions(LocalDateTime now) {
        Set<String> recent = usageMapper.recentSourceKeys(now.minusDays(7));
        return dataSourceMapper.enabledSources().stream()
            .filter(source -> "ok".equals(source.getProbeStatus()))
            .filter(source -> !recent.contains(source.getSourceKey()))
            .sorted(java.util.Comparator.comparing(HeronDataSource::getName,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .limit(DATASET_POOL_LIMIT)
            .map(source -> source.getName() + "（" + source.getCadence() + "，" + source.getShapes() + "）")
            .toList();
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


    private Set<String> autoHosts() {
        List<HeronNewsSource> sources = sourceMapper.selectList(QueryBuilder.lambda(HeronNewsSource.class)
            .eq(HeronNewsSource::getEnabled, "0").eq(HeronNewsSource::getAuto, "0").build());
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
     * 决策结构化落库。detail 只是一段给人看的文本，编辑策划页要能回答「它看到了什么、为什么这么选」，
     * 所以线、意图、理由和候选池快照分开存。NO_PUBLISH 也留痕，否则空槽看起来像故障。
     */
    private void markDecision(HeronAutoSlot slot, EditorialDecisionService.Decision decision,
                              EditorialDecisionService.Pools pools) {
        slotMapper.lambda()
            .set(HeronAutoSlot::getSelectedLine, decision.publish() ? decision.selectedLine()
                : EditorialDecisionService.NO_PUBLISH)
            .set(HeronAutoSlot::getEditorialIntent, clip(decision.editorialIntent(), 500))
            .set(HeronAutoSlot::getDecisionReason, clip(decision.reason(), 500))
            .set(HeronAutoSlot::getPoolJson, JsonUtils.toJsonString(pools))
            .set(HeronAutoSlot::getUpdateTime, LocalDateTime.now(clock))
            .eq(HeronAutoSlot::getSlotDate, slot.getSlotDate())
            .eq(HeronAutoSlot::getSlotTime, slot.getSlotTime())
            .update();
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
