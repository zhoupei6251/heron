package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronAutoSlot;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.domain.vo.StrategyReferenceVo;
import org.dromara.heron.mapper.HeronAutoSlotMapper;
import org.dromara.heron.service.INewsMetricService;
import org.dromara.heron.service.impl.shared.research.StrategyReferenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参考卡：只给人看、样本不足不给聚合值、被锁的维度保留卡但不展示结论。 */
@DisplayName("表现反馈：参考卡")
class StrategyReferenceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);

    /** 桩：只给归因服务与槽位表，不碰真库。 */
    private static final class StubMetricService implements INewsMetricService {
        private final NewsPerformanceInsightVo insight;

        StubMetricService(NewsPerformanceInsightVo insight) {
            this.insight = insight;
        }

        @Override
        public org.dromara.heron.domain.vo.NewsMetricVo save(
            org.dromara.heron.domain.bo.NewsMetricBo bo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<org.dromara.heron.domain.vo.NewsMetricVo> byVersion(long versionId) {
            return List.of();
        }

        @Override
        public List<org.dromara.heron.mapper.HeronNewsMetricMapper.Performance> recentPerformance(int limit) {
            return List.of();
        }

        @Override
        public NewsPerformanceInsightVo performanceInsight(String dimension) {
            return insight;
        }

        @Override
        public List<org.dromara.heron.domain.vo.NewsSuggestionVo> suggestions(int limit) {
            return List.of();
        }
    }

    /** 桩：槽位表只回固定的几行。 */
    private static HeronAutoSlotMapper stubSlots(List<HeronAutoSlot> slots) {
        HeronAutoSlotMapper mapper = org.mockito.Mockito.mock(HeronAutoSlotMapper.class);
        org.mockito.Mockito.when(mapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(slots);
        return mapper;
    }

    private static NewsPerformanceInsightVo insight(String tier, Double median, int samples) {
        NewsPerformanceInsightVo vo = new NewsPerformanceInsightVo();
        vo.setDimension("column");
        vo.setTier(tier);
        vo.setTotalSamples(samples);
        NewsPerformanceInsightVo.Bucket bucket = new NewsPerformanceInsightVo.Bucket();
        bucket.setBucket("全球市场");
        bucket.setSamples(samples);
        bucket.setTier(tier);
        bucket.setMedianPlays(median);
        bucket.setStable(true);
        bucket.setStrategyEligible("L4".equals(tier));
        bucket.setSummary("全球市场：" + samples + " 条样本");
        vo.setBuckets(new java.util.ArrayList<>(List.of(bucket)));
        return vo;
    }

    private static HeronAutoSlot slot(String override) {
        HeronAutoSlot slot = new HeronAutoSlot();
        slot.setSlotDate(java.time.LocalDate.of(2026, 11, 20));
        slot.setSlotTime("0 0 9 * * ?");
        slot.setStrategyOverride(override);
        return slot;
    }

    @Test
    @DisplayName("档位够才给聚合值，不够只给样本数")
    void aggregateOnlyWhenTierSufficient() {
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L1", null, 2)), stubSlots(List.of()));

        StrategyReferenceVo vo = service.reference("column", "全球市场");

        assertEquals(1, vo.getBuckets().size());
        assertEquals(2, vo.getBuckets().get(0).getSamples());
        assertNull(vo.getBuckets().get(0).getMedianPlays(), "样本不足时不给中位数");
    }

    @Test
    @DisplayName("被锁定的维度保留卡，但不给聚合结论")
    void lockedDimensionKeepsCardButHidesConclusion() {
        // 生效中的锁必须真的在未来：写死日期的夹具到点就会翻车（11-26 就翻过一次）
        String until = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String override = "{\"action\":\"lock\",\"scope\":\"column:全球市场\",\"reason\":\"上周三条完播都低\","
            + "\"decidedBy\":1,\"decidedAt\":\"2026-11-19T10:00:00\",\"scopeUntil\":\"" + until + "\"}";
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L4", 100.0, 30)), stubSlots(List.of(slot(override))));

        StrategyReferenceVo vo = service.reference("column", "全球市场");

        assertTrue(vo.isLocked());
        assertEquals("上周三条完播都低", vo.getLockReason());
        assertNotNull(vo.getScopeUntil());
        assertTrue(vo.getBuckets().isEmpty(), "锁定的维度不给聚合结论");
        assertTrue(vo.getNotice().contains("锁定"), vo.getNotice());
    }

    @Test
    @DisplayName("过期的锁不再生效")
    void expiredLockIsNotActive() {
        String override = "{\"action\":\"lock\",\"scope\":\"column:全球市场\",\"reason\":\"过期的锁\","
            + "\"decidedBy\":1,\"decidedAt\":\"2026-10-01T10:00:00\",\"scopeUntil\":\"2026-10-08T00:00:00\"}";
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L4", 100.0, 30)), stubSlots(List.of(slot(override))));

        StrategyReferenceVo vo = service.reference("column", "全球市场");

        assertFalse(vo.isLocked());
        assertEquals(1, vo.getBuckets().size());
    }

    @Test
    @DisplayName("覆盖记录缺理由、缺操作人、缺期限都不许落库")
    void overrideValidation() {
        StrategyReferenceService.Override noReason = new StrategyReferenceService.Override(
            "lock", "column:全球市场", "", "", 1L, NOW, NOW.plusDays(7), "manual");
        assertThrows(HeronBusinessException.class, () -> StrategyReferenceService.validate(noReason));

        StrategyReferenceService.Override noActor = new StrategyReferenceService.Override(
            "lock", "column:全球市场", "", "理由", null, NOW, NOW.plusDays(7), "manual");
        assertThrows(HeronBusinessException.class, () -> StrategyReferenceService.validate(noActor));

        StrategyReferenceService.Override noExpiry = new StrategyReferenceService.Override(
            "lock", "column:全球市场", "", "理由", 1L, NOW, null, "manual");
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> StrategyReferenceService.validate(noExpiry));
        assertTrue(error.getMessage().contains("到期时间"), error.getMessage());

        StrategyReferenceService.Override longReason = new StrategyReferenceService.Override(
            "lock", "column:全球市场", "", "理".repeat(501), 1L, NOW, NOW.plusDays(7), "manual");
        assertThrows(HeronBusinessException.class, () -> StrategyReferenceService.validate(longReason));
    }

    @Test
    @DisplayName("作用域匹配：维度对上，分组为空也算命中")
    void scopeMatching() {
        assertTrue(StrategyReferenceService.matches("column:全球市场", "column", "全球市场"));
        assertTrue(StrategyReferenceService.matches("column:全球市场", "column", null));
        assertFalse(StrategyReferenceService.matches("column:全球市场", "template", "全球市场"));
        assertFalse(StrategyReferenceService.matches("column:全球市场", "column", "科技板块"));
    }

    @Test
    @DisplayName("未知维度直接拒绝")
    void unknownDimensionRejected() {
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L0", null, 0)), stubSlots(List.of()));
        assertThrows(HeronBusinessException.class, () -> service.reference("plays; drop table", null));
    }

    @Test
    @DisplayName("坏掉的覆盖记录读不出来时只当没有覆盖")
    void brokenOverrideIsIgnored() {
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L4", 100.0, 30)),
            stubSlots(List.of(slot("{not json"))));

        StrategyReferenceVo vo = service.reference("column", "全球市场");

        assertFalse(vo.isLocked());
        assertEquals(1, vo.getBuckets().size());
    }

    @Test
    @DisplayName("当前生效的锁能被单独读出来")
    void activeLocksAreListed() {
        String until = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String override = "{\"action\":\"lock\",\"scope\":\"column:全球市场\",\"reason\":\"理由\","
            + "\"decidedBy\":1,\"decidedAt\":\"2026-11-19T10:00:00\",\"scopeUntil\":\"" + until + "\"}";
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L4", 100.0, 30)), stubSlots(List.of(slot(override))));

        List<StrategyReferenceService.Override> locks = service.activeLocks();

        assertEquals(1, locks.size());
        assertEquals("column:全球市场", locks.get(0).scope());
    }

    @Test
    @DisplayName("参考卡不是策略开关：它自己不带可用标记")
    void referenceCardIsNotASwitch() {
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L4", 100.0, 30)), stubSlots(List.of()));

        StrategyReferenceVo vo = service.reference("column", "全球市场");

        assertTrue(vo.getBuckets().get(0).isStrategyEligible(), "L4 且稳定才允许进策略");
        assertTrue(vo.getNotice().contains("不改自动策略"), vo.getNotice());
    }

    @Test
    @DisplayName("解析不出来的覆盖记录返回空")
    void parseEmptyOverride() {
        StrategyReferenceService service = new StrategyReferenceService(
            new StubMetricService(insight("L0", null, 0)), stubSlots(List.of()));
        assertEquals(Optional.empty(), service.parse(null));
        assertEquals(Optional.empty(), service.parse(""));
    }
}
