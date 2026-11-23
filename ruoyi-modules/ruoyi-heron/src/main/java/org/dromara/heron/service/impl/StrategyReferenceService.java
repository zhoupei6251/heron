package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronAutoSlot;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.domain.vo.StrategyReferenceVo;
import org.dromara.heron.mapper.HeronAutoSlotMapper;
import org.dromara.heron.service.INewsMetricService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 历史表现参考卡：把「已有样本里这一组的表现如何」摆给人看。
 *
 * <p>它不是自动开关。三条硬边界：
 * 一是表现数据不自动改变策略，参考卡只提供事实，用不用由总编辑决定；
 * 二是样本量门槛先于一切，档位没到的分组不给聚合值；
 * 三是人工覆盖必须显式记录，被锁定的维度显示「已锁定 + 理由 + 期限」，
 * 而不是把卡藏起来——人要看得见自己锁了什么。
 */
@Service
@RequiredArgsConstructor
public class StrategyReferenceService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SCHEMA_VERSION = "strategy-reference/1";
    /** 覆盖理由的长度上限与槽位的决策理由一致。 */
    static final int REASON_LIMIT = 500;
    /** 锁定动作。 */
    static final String ACTION_LOCK = "lock";
    static final String ACTION_VETO = "veto";
    static final String ACTION_OVERRIDE = "override";
    static final String ACTION_UNLOCK = "unlock";

    private final INewsMetricService metrics;
    private final HeronAutoSlotMapper slots;

    /**
     * 出一张参考卡。
     *
     * @param dimension   归因维度，必须在 {@link MetricInsightAnalyzer#DIMENSIONS} 里
     * @param bucketValue 正在看的分组值；为空时给出该维度下所有分组
     */
    public StrategyReferenceVo reference(String dimension, String bucketValue) {
        String key = dimension == null ? "" : dimension.strip().toLowerCase();
        if (!MetricInsightAnalyzer.DIMENSIONS.contains(key)) {
            throw new HeronBusinessException(400, "metric_dimension_invalid",
                "只支持按 " + String.join(" / ", MetricInsightAnalyzer.DIMENSIONS) + " 汇总，收到：" + dimension);
        }
        NewsPerformanceInsightVo insight = metrics.performanceInsight(key);
        StrategyReferenceVo vo = new StrategyReferenceVo();
        vo.setSchemaVersion(SCHEMA_VERSION);
        vo.setGeneratedAt(LocalDateTime.now(ZoneOffset.UTC));
        vo.setDimension(key);
        vo.setBucketValue(bucketValue);
        vo.setTier(insight.getTier());
        vo.setBuckets(buckets(insight, bucketValue));
        Optional<Override> locked = activeLock(key, bucketValue);
        if (locked.isPresent()) {
            vo.setLocked(true);
            vo.setLockReason(locked.get().reason());
            vo.setScopeUntil(locked.get().scopeUntil());
            // 被锁的维度不给聚合结论：锁是给自动建议用的，人要看的是自己锁了什么。
            vo.setBuckets(List.of());
            vo.setNotice("本维度已被人工锁定：" + locked.get().reason()
                + "（至 " + locked.get().scopeUntil() + "）。参考卡保留在页面上，但不展示聚合结论。");
            return vo;
        }
        vo.setNotice("本卡只做相关观察，不改自动策略；结论能说到什么程度看证据等级，"
            + "样本不足的分组只给原始样本。");
        return vo;
    }

    private List<StrategyReferenceVo.Bucket> buckets(NewsPerformanceInsightVo insight, String bucketValue) {
        List<StrategyReferenceVo.Bucket> out = new ArrayList<>();
        for (NewsPerformanceInsightVo.Bucket bucket : insight.getBuckets()) {
            if (bucketValue != null && !bucketValue.isBlank() && !bucketValue.equals(bucket.getBucket())) {
                continue;
            }
            StrategyReferenceVo.Bucket item = new StrategyReferenceVo.Bucket();
            item.setBucket(bucket.getBucket());
            item.setSamples(bucket.getSamples());
            item.setTier(bucket.getTier());
            item.setMedianPlays(bucket.getMedianPlays());
            item.setP25Plays(bucket.getP25Plays());
            item.setP75Plays(bucket.getP75Plays());
            item.setOutlierRatio(bucket.getOutlierRatio());
            item.setStable(bucket.isStable());
            item.setStrategyEligible(bucket.isStrategyEligible());
            item.setSummary(bucket.getSummary());
            out.add(item);
        }
        return out;
    }

    /**
     * 追加一条人工覆盖记录。
     *
     * <p>覆盖不改写原决策，只追加一层。要解除就再写一条 unlock——
     * 删记录等于让策略被悄悄改过，没人记得。
     */
    public void appendOverride(java.time.LocalDate slotDate, String slotTime, Override override) {
        validate(override);
        // 真实键是 日期+时刻：同一天有多个槽位，selectById(slotDate) 会 selectOne 撞多行
        HeronAutoSlot slot = slots.selectOne(org.dromara.common.mybatis.core.query.QueryBuilder.lambda(HeronAutoSlot.class)
            .eq(HeronAutoSlot::getSlotDate, slotDate)
            .eq(HeronAutoSlot::getSlotTime, slotTime).build());
        if (slot == null) {
            throw new HeronBusinessException(404, "slot_missing", "槽位不存在");
        }
        String record = JSON.writeValueAsString(override);
        slots.lambda().set(HeronAutoSlot::getStrategyOverride, record)
            .eq(HeronAutoSlot::getSlotDate, slotDate)
            .eq(HeronAutoSlot::getSlotTime, slotTime)
            .update();
    }

    /** 覆盖记录的必填项。缺任何一项都不落库：没有理由的覆盖以后说不清。 */
    static void validate(Override override) {
        if (override == null) {
            throw new HeronBusinessException(400, "override_invalid", "覆盖记录不能为空");
        }
        if (!List.of(ACTION_LOCK, ACTION_VETO, ACTION_OVERRIDE, ACTION_UNLOCK).contains(override.action())) {
            throw new HeronBusinessException(400, "override_invalid", "未知覆盖动作：" + override.action());
        }
        if (override.reason() == null || override.reason().isBlank()) {
            throw new HeronBusinessException(400, "override_invalid", "覆盖必须写明理由");
        }
        if (override.reason().length() > REASON_LIMIT) {
            throw new HeronBusinessException(400, "override_invalid", "覆盖理由超过 " + REASON_LIMIT + " 字");
        }
        if (override.decidedBy() == null) {
            throw new HeronBusinessException(400, "override_invalid", "覆盖必须记录操作人");
        }
        if (override.decidedAt() == null) {
            throw new HeronBusinessException(400, "override_invalid", "覆盖必须记录时间");
        }
        if (ACTION_LOCK.equals(override.action()) && override.scopeUntil() == null) {
            throw new HeronBusinessException(400, "override_invalid", "锁定必须有到期时间：没有期限的锁会永久改变策略");
        }
    }

    /**
     * 当前生效的锁定清单。
     *
     * <p>锁只对自动建议生效，但人必须看得见自己锁了什么；
     * 提示里只出现锁本身（作用域与理由），不出现表现数据——
     * 表现数据进提示词等于给模型一个策略倾向。
     */
    public List<Override> activeLocks() {
        List<Override> locks = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (HeronAutoSlot slot : slots.selectList(
            org.dromara.common.mybatis.core.query.QueryBuilder.lambda(HeronAutoSlot.class)
                .isNotNull(HeronAutoSlot::getStrategyOverride)
                .orderByDesc(HeronAutoSlot::getSlotDate).build())) {
            Optional<Override> parsed = parse(slot.getStrategyOverride());
            if (parsed.isEmpty() || !ACTION_LOCK.equals(parsed.get().action())) {
                continue;
            }
            Override item = parsed.get();
            if (item.scopeUntil() != null && item.scopeUntil().isBefore(now)) {
                continue;
            }
            locks.add(item);
        }
        return locks;
    }

    /** 当前生效的锁定。解锁记录与过期锁定都不生效。 */
    Optional<Override> activeLock(String dimension, String bucketValue) {
        List<HeronAutoSlot> all = slots.selectList(
            org.dromara.common.mybatis.core.query.QueryBuilder.lambda(HeronAutoSlot.class)
                .isNotNull(HeronAutoSlot::getStrategyOverride)
                .orderByDesc(HeronAutoSlot::getSlotDate).build());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Override found = null;
        for (HeronAutoSlot slot : all) {
            Optional<Override> parsed = parse(slot.getStrategyOverride());
            if (parsed.isEmpty()) {
                continue;
            }
            Override item = parsed.get();
            if (!ACTION_LOCK.equals(item.action())) {
                continue;
            }
            if (!matches(item.scope(), dimension, bucketValue)) {
                continue;
            }
            if (item.scopeUntil() != null && item.scopeUntil().isBefore(now)) {
                continue;
            }
            // 最新一条生效的锁定为准。
            if (found == null || item.decidedAt().isAfter(found.decidedAt())) {
                found = item;
            }
        }
        return Optional.ofNullable(found);
    }

    /** 锁定作用域形如 column:market；维度对上、分组为空或对上才算命中。 */
    static boolean matches(String scope, String dimension, String bucketValue) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        int colon = scope.indexOf(':');
        if (colon < 0) {
            return dimension.equalsIgnoreCase(scope);
        }
        String scopeDimension = scope.substring(0, colon);
        String scopeBucket = scope.substring(colon + 1);
        if (!scopeDimension.equalsIgnoreCase(dimension)) {
            return false;
        }
        return bucketValue == null || bucketValue.isBlank() || scopeBucket.equals(bucketValue);
    }

    Optional<Override> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            LocalDateTime decidedAt = node.path("decidedAt").isTextual()
                ? LocalDateTime.parse(node.path("decidedAt").asString()) : null;
            LocalDateTime scopeUntil = node.path("scopeUntil").isTextual()
                ? LocalDateTime.parse(node.path("scopeUntil").asString()) : null;
            Long decidedBy = node.path("decidedBy").isNumber() ? node.path("decidedBy").asLong() : null;
            return Optional.of(new Override(
                node.path("action").asString(""),
                node.path("scope").asString(""),
                node.path("value").asString(""),
                node.path("reason").asString(""),
                decidedBy,
                decidedAt,
                scopeUntil,
                node.path("source").asString("manual")));
        } catch (RuntimeException e) {
            // 覆盖记录读不出来只当没有覆盖：不让一条坏记录把整个参考卡打不开。
            return Optional.empty();
        }
    }

    /** 一条人工覆盖记录。 */
    public record Override(String action, String scope, String value, String reason, Long decidedBy,
                           LocalDateTime decidedAt, LocalDateTime scopeUntil, String source) {
    }
}
