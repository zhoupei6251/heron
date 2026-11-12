package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.config.HeronNewsProperties;
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
    private final HeronDataSourceMapper dataSourceMapper;
    private final HeronDatasetUsageMapper usageMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public NewsAutoService(HeronNewsProperties news, HeronNewsSourceMapper sourceMapper,
                           HeronNewsCandidateMapper candidateMapper, HeronNewsWorkMapper workMapper,
                           HeronVideoVersionMapper versionMapper, HeronAutoSlotMapper slotMapper,
                           NewsWorkServiceImpl newsWork, AutoPauseStore pauseStore, NewsCandidateScreener screener,
                           EditorialDecisionService editorial, NewsProductionDomain domain,
                           HeronDataSourceMapper dataSourceMapper, HeronDatasetUsageMapper usageMapper) {
        this(news, sourceMapper, candidateMapper, workMapper, versionMapper, slotMapper, newsWork, pauseStore,
            screener, editorial, domain, dataSourceMapper, usageMapper, Clock.systemUTC());
    }

    public NewsAutoService(HeronNewsProperties news, HeronNewsSourceMapper sourceMapper,
                           HeronNewsCandidateMapper candidateMapper, HeronNewsWorkMapper workMapper,
                           HeronVideoVersionMapper versionMapper, HeronAutoSlotMapper slotMapper,
                           NewsWorkServiceImpl newsWork, AutoPauseStore pauseStore, NewsCandidateScreener screener,
                           EditorialDecisionService editorial, NewsProductionDomain domain,
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
        Optional<Long> candidateId = pickCandidate(now);
        if (candidateId.isEmpty()) {
            finish(slot, "skipped", "no_candidate", null);
            return;
        }
        EditorialDecisionService.Decision decision;
        EditorialDecisionService.Pools pools;
        try {
            pools = pools(candidateId.get(), now);
            decision = editorial.decide(pools);
        } catch (RuntimeException e) {
            // 决策失败不产出：宁可空槽，也不按默认线硬发
            finish(slot, "failed", "editorial_decision: " + e.getMessage(), null);
            return;
        }
        markDecision(slot, decision, candidateId.get(), pools);
        if (!decision.publish()) {
            finish(slot, "skipped", "no_publish: " + decision.reason(), null);
            return;
        }
        String template = templateFor(decision.selectedLine());
        if (template == null) {
            finish(slot, "failed", "no_template_for_" + decision.selectedLine(), null);
            return;
        }
        try {
            NewsWorkVo work = newsWork.claimForAuto(candidateId.get(), news.getAutoActorId(), template);
            newsWork.generateForAuto(work.getWorkId(), "auto:" + today + ":" + slotTime);
            finish(slot, "produced", decision.selectedLine() + "：" + decision.editorialIntent(), work.getWorkId());
        } catch (RuntimeException e) {
            // 失败留痕即可；下一槽位会重新挑，不跨槽位补发
            log.warn("自动产出槽位失败 slot={} {}: {}", slotTime, e.getClass().getSimpleName(), e.getMessage());
            finish(slot, "failed", e.getMessage(), null);
        }
    }

    /** 总编辑定的线映射到模板：数据看板取 news-data-*，解说线取其余 news-*。 */
    private String templateFor(String line) {
        boolean dataBoard = EditorialDecisionService.DATA_BOARD.equals(line);
        return domain.availableTemplates().stream()
            .filter(key -> key.startsWith("news-data-") == dataBoard)
            .findFirst().orElse(null);
    }

    /** 两条线的候选概况：只给概况，全量条目留给线内编辑。 */
    private EditorialDecisionService.Pools pools(long candidateId, LocalDateTime now) {
        Set<String> autoHosts = autoHosts();
        List<HeronNewsCandidate> pool = candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
            .isNull(HeronNewsCandidate::getPublishedAt)
            .ge(HeronNewsCandidate::getLastSeenAt, now.minusHours(24))
            .orderByDesc(HeronNewsCandidate::getHeatScore)
            .last("LIMIT " + POOL_LIMIT).build());
        Set<String> articleSources = sourceMapper.selectList(QueryBuilder.lambda(HeronNewsSource.class)
                .eq(HeronNewsSource::getSourceType, "rss").build())
            .stream().map(source -> String.valueOf(source.getSourceId()))
            .collect(java.util.stream.Collectors.toSet());
        List<String> hot = new ArrayList<>();
        List<String> articles = new ArrayList<>();
        for (HeronNewsCandidate candidate : pool) {
            String title = candidate.getTitle();
            if (title == null || title.isBlank()) continue;
            if (articleSources.contains(sourceIdOf(candidate))) {
                articles.add(title);
            } else {
                hot.add(title);
            }
        }
        List<String> sources = dataSourceOptions(now);
        List<String> recent = workMapper.selectList(QueryBuilder.lambda(HeronNewsWork.class)
                .orderByDesc(HeronNewsWork::getUpdateTime).last("LIMIT " + RECENT_LIMIT).build())
            .stream().map(HeronNewsWork::getTitle).filter(title -> title != null && !title.isBlank())
            .toList();
        if (autoHosts.isEmpty()) {
            log.warn("自动源白名单为空，候选池可能不可用");
        }
        return new EditorialDecisionService.Pools(hot, articles, sources, recent);
    }

    /** 候选证据里的 sourceId，用来区分「有正文的 RSS」和「只有标题的热榜」。 */
    private static String sourceIdOf(HeronNewsCandidate candidate) {
        try {
            JsonNode items = JsonUtils.getJsonMapper().readTree(candidate.getEvidence()).path("items");
            return items.isArray() && !items.isEmpty() ? items.get(0).path("sourceId").asString("") : "";
        } catch (RuntimeException e) {
            return "";
        }
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

    /** 候选选择：源开关 → 24 小时 → 未认领未在途未发布 → 热度降序 → 同源不连续。 */
    private Optional<Long> pickCandidate(LocalDateTime now) {
        Set<String> autoHosts = autoHosts();
        if (autoHosts.isEmpty()) {
            return Optional.empty();
        }
        String lastHost = lastAutoHost();
        List<HeronNewsCandidate> pool = candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
            .isNull(HeronNewsCandidate::getClaimedBy)
            .isNull(HeronNewsCandidate::getActiveTaskId)
            .isNull(HeronNewsCandidate::getPublishedAt)
            .ge(HeronNewsCandidate::getLastSeenAt, now.minusHours(24))
            .orderByDesc(HeronNewsCandidate::getHeatScore)
            .orderByAsc(HeronNewsCandidate::getBestRank)
            .last("LIMIT " + POOL_LIMIT).build());
        List<HeronNewsCandidate> eligible = new ArrayList<>();
        for (HeronNewsCandidate candidate : pool) {
            String host = firstHost(candidate.getEvidence());
            if (host == null || !autoHosts.contains(host) || host.equals(lastHost)) {
                continue;
            }
            eligible.add(candidate);
        }
        if (eligible.isEmpty()) {
            // 只有一个源开着自动时，「同源不连续」会把池子清空；退回不限同源的候选，避免永远不产出
            for (HeronNewsCandidate candidate : pool) {
                String host = firstHost(candidate.getEvidence());
                if (host != null && autoHosts.contains(host)) {
                    eligible.add(candidate);
                }
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        // 模型按《选题抽卡规则》挑一条；模型不可用时退回热度最高的那条
        return Optional.of(screener.screen(eligible).orElse(eligible.get(0).getCandidateId()));
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

    /** 上一次自动产出的来源主机：同源不连续的依据，取自最近一条自动稿件。 */
    private String lastAutoHost() {
        HeronNewsWork last = workMapper.selectOne(QueryBuilder.lambda(HeronNewsWork.class)
            .eq(HeronNewsWork::getAutoPublish, "0")
            .orderByDesc(HeronNewsWork::getCreateTime).last("LIMIT 1").build());
        if (last == null) {
            return null;
        }
        HeronNewsCandidate candidate = candidateMapper.selectById(last.getCandidateId());
        return candidate == null ? null : firstHost(candidate.getEvidence());
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
                              long candidateId, EditorialDecisionService.Pools pools) {
        slotMapper.lambda()
            .set(HeronAutoSlot::getSelectedLine, decision.publish() ? decision.selectedLine()
                : EditorialDecisionService.NO_PUBLISH)
            .set(HeronAutoSlot::getEditorialIntent, clip(decision.editorialIntent(), 500))
            .set(HeronAutoSlot::getDecisionReason, clip(decision.reason(), 500))
            .set(HeronAutoSlot::getCandidateId, candidateId)
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
