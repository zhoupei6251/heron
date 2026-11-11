package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** facts 归约口径：四种 shape 各一条，哈希确定性一条。 */
@DisplayName("数据看板：facts 归约")
class DatasetFactsTest {

    private static DatasetFacts.Row row(Object... cells) {
        return new DatasetFacts.Row(Arrays.asList(cells));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("wide：首末、极值、变化与年份")
    void wide() {
        Map<String, Object> facts = DatasetFacts.compute("wide", List.of(
            row("2025-12-31", decimal("100.00")),
            row("2026-01-02", decimal("110.00")),
            row("2026-01-03", decimal("90.00"))), 2);
        assertEquals(100.0, ((Map<?, ?>) facts.get("first")).get("value"));
        assertEquals(90.0, ((Map<?, ?>) facts.get("last")).get("value"));
        assertEquals(90.0, ((Map<?, ?>) facts.get("min")).get("value"));
        assertEquals(110.0, ((Map<?, ?>) facts.get("max")).get("value"));
        assertEquals(-10.0, (Double) facts.get("change"), 0.0001);
        assertEquals(-10.0, (Double) facts.get("change_pct"), 0.0001);
        assertEquals(2025, ((Map<?, ?>) facts.get("period")).get("start_year"));
        assertEquals(2026, ((Map<?, ?>) facts.get("period")).get("end_year"));
    }

    @Test
    @DisplayName("long：按首次出现顺序分组，leader 取最大 last")
    void longShape() {
        Map<String, Object> facts = DatasetFacts.compute("long", List.of(
            row("2026-11-09", "北京", decimal("10.0")),
            row("2026-11-09", "上海", decimal("15.0")),
            row("2026-11-10", "北京", decimal("12.0")),
            row("2026-11-10", "上海", decimal("14.0"))), 1);
        List<?> series = (List<?>) facts.get("series");
        assertEquals(2, series.size());
        Map<?, ?> beijing = (Map<?, ?>) series.get(0);
        assertEquals("北京", beijing.get("name"));
        assertEquals(10.0, beijing.get("first"));
        assertEquals(12.0, beijing.get("last"));
        assertEquals(2.0, beijing.get("change"));
        assertEquals(20.0, beijing.get("change_pct"));
        Map<?, ?> leader = (Map<?, ?>) facts.get("leader");
        assertEquals("上海", leader.get("name"));
        assertEquals(14.0, leader.get("value"));
    }

    @Test
    @DisplayName("long：乱序行按日期取首末；截止更早的系列不参与 leader")
    void longOutOfOrderAndUnevenEnd() {
        Map<String, Object> facts = DatasetFacts.compute("long", List.of(
            row("2026-11-10", "北京", decimal("12.0")),
            row("2026-11-09", "北京", decimal("10.0")),
            row("2026-11-09", "上海", decimal("99.0"))), 1);
        Map<?, ?> beijing = (Map<?, ?>) ((List<?>) facts.get("series")).get(0);
        assertEquals(10.0, beijing.get("first"), "首值要按日期取，不能按插入顺序");
        assertEquals(12.0, beijing.get("last"), "末值要按日期取，不能按插入顺序");
        Map<?, ?> leader = (Map<?, ?>) facts.get("leader");
        assertEquals("北京", leader.get("name"), "上海 99.0 停在 11-09，不能和 11-10 的数比大小");
        assertEquals(12.0, leader.get("value"));
    }

    @Test
    @DisplayName("rows：leader、spread、count，change 可缺省")
    void rowsShape() {
        Map<String, Object> facts = DatasetFacts.compute("rows", List.of(
            row("金", decimal("4100.00"), decimal("1.20")),
            row("银", decimal("30.00"), decimal("-0.50")),
            row("铜", decimal("7000.00"), null)), 2);
        List<?> items = (List<?>) facts.get("items");
        assertEquals(3, items.size());
        assertFalse(((Map<?, ?>) items.get(2)).containsKey("change"));
        Map<?, ?> leader = (Map<?, ?>) facts.get("leader");
        assertEquals("铜", leader.get("label"));
        assertEquals(3, facts.get("count"));
        assertEquals(6970.0, (Double) facts.get("spread"), 0.0001);
    }

    @Test
    @DisplayName("geo：leader 与 count 只看 value")
    void geoShape() {
        Map<String, Object> facts = DatasetFacts.compute("geo", List.of(
            row("北京", decimal("39.9"), decimal("116.4"), decimal("12.0")),
            row("上海", decimal("31.2"), decimal("121.5"), decimal("15.0"))), 1);
        Map<?, ?> leader = (Map<?, ?>) facts.get("leader");
        assertEquals("上海", leader.get("location"));
        assertEquals(15.0, leader.get("value"));
        assertEquals(2, facts.get("count"));
    }

    @Test
    @DisplayName("哈希：同数据同哈希，改一个值即不同")
    void hashIsDeterministic() {
        List<DatasetFacts.Row> rows = List.of(row("2026-11-10", decimal("4113.34")));
        DatasetFacts.Metric metric = new DatasetFacts.Metric("close", "USD/oz", 2);
        String first = DatasetFacts.sha256("wide", List.of("date", "value"), metric, rows);
        String second = DatasetFacts.sha256("wide", List.of("date", "value"), metric, rows);
        String changed = DatasetFacts.sha256("wide", List.of("date", "value"), metric,
            List.of(row("2026-11-10", decimal("4113.35"))));
        assertEquals(first, second);
        assertNotEquals(first, changed);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }
}
