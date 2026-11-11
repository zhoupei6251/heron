package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 选源决策：热点命中、去重降级速览、空槽告警。 */
@DisplayName("数据看板：选源与去重降级")
class DatasetRouterTest {

    private static final Set<String> ENABLED = Set.of("sina-gold-xau", "sina-oil-cl");
    private static final LocalDateTime OLD = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime NEW = LocalDateTime.of(2026, 11, 5, 0, 0);

    private static HeronDataSource source(String key, LocalDateTime updated) {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey(key);
        source.setName(key);
        source.setLastUpdated(updated);
        return source;
    }

    @Test
    @DisplayName("清单内命中就是热点片")
    void hotPickStaysHot() {
        DatasetRouter.Route route = DatasetRouter.plan("{\"source_key\":\"sina-gold-xau\"}",
            List.of(source("sina-gold-xau", OLD), source("sina-oil-cl", NEW)), ENABLED);
        assertEquals("sina-gold-xau", route.sourceKey());
        assertFalse(route.digest());
    }

    @Test
    @DisplayName("模型说 none 时降级速览，取最近更新的源")
    void noneFallsBackToDigest() {
        DatasetRouter.Route route = DatasetRouter.plan("{\"source_key\":\"none\"}",
            List.of(source("sina-gold-xau", OLD), source("sina-oil-cl", NEW)), ENABLED);
        assertEquals("sina-oil-cl", route.sourceKey());
        assertTrue(route.digest());
    }

    @Test
    @DisplayName("命中近 7 天已用源时同样降级速览，不硬出重复片")
    void dedupedPickFallsBackToDigest() {
        DatasetRouter.Route route = DatasetRouter.plan("{\"source_key\":\"sina-gold-xau\"}",
            List.of(source("sina-oil-cl", NEW)), ENABLED);
        assertEquals("sina-oil-cl", route.sourceKey());
        assertTrue(route.digest());
    }

    @Test
    @DisplayName("选了未启用的源、缺字段或非 JSON 都直接失败")
    void rejectsInvalidRoute() {
        assertTrue(assertThrows(HeronBusinessException.class, () -> DatasetRouter.plan(
            "{\"source_key\":\"maoyan-daily\"}", List.of(source("sina-gold-xau", OLD)), ENABLED))
            .getMessage().contains("未启用"));
        assertTrue(assertThrows(HeronBusinessException.class, () -> DatasetRouter.plan(
            "{}", List.of(source("sina-gold-xau", OLD)), ENABLED)).getMessage().contains("缺少 source_key"));
        assertTrue(assertThrows(HeronBusinessException.class, () -> DatasetRouter.plan(
            "not json", List.of(source("sina-gold-xau", OLD)), ENABLED)).getMessage().contains("不是 JSON"));
    }

    @Test
    @DisplayName("速览栏内优先日更源，年度源不能因为抓取时间新就排前面")
    void digestPrefersDailyCadence() {
        HeronDataSource annual = source("worldbank-gdp", NEW);
        annual.setCadence("annual");
        HeronDataSource daily = source("worldbank-population", OLD);
        daily.setCadence("daily");
        DatasetRouter.Route route = DatasetRouter.digest(List.of(annual, daily));
        assertEquals("worldbank-population", route.sourceKey());
        assertTrue(route.digest());
    }

    @Test
    @DisplayName("权重轮换 20 期：科技 6、市场 4、商业 4、趋势 3、图鉴 3")
    void weightedCycle() {
        List<Column> cycle = DatasetRouter.cycle();
        assertEquals(20, cycle.size());
        assertEquals(6, cycle.stream().filter(c -> c == Column.TECH).count());
        assertEquals(4, cycle.stream().filter(c -> c == Column.MARKET).count());
        assertEquals(4, cycle.stream().filter(c -> c == Column.BUSINESS).count());
        assertEquals(3, cycle.stream().filter(c -> c == Column.TREND).count());
        assertEquals(3, cycle.stream().filter(c -> c == Column.ATLAS).count());
    }

    @Test
    @DisplayName("没有可用源时给出空槽告警")
    void emptyEligibleIsSlotEmpty() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> DatasetRouter.digest(List.of()));
        assertEquals("dataset_slot_empty", error.getCode());
    }
}
