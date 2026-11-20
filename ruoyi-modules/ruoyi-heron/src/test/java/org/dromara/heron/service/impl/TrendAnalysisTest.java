package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 趋势判定必须是算出来的：同一序列换模型也得同一结论。
 *
 * <p>这些用例照的是设计里的四条红线：区间波动不许说成长期趋势、
 * 样本不足不许判方向、变化原因与后续走势只能是推测、关键结论必须落到证据上。
 */
@DisplayName("趋势线：确定性判定")
class TrendAnalysisTest {

    private static final LocalDate START = LocalDate.parse("2026-01-01");

    private static ObjectNode dataset(String sourceKey, double... values) {
        ObjectNode dataset = JsonNodeFactory.instance.objectNode();
        dataset.put("contract", "dataset/1");
        dataset.put("source_key", sourceKey);
        dataset.put("source_name", "示例序列");
        dataset.put("cadence", "daily");
        dataset.put("shape", "wide");
        ObjectNode metric = dataset.putObject("metric");
        metric.put("key", "attention");
        metric.put("label", "关注指数");
        metric.put("unit", "点");
        metric.put("precision", 2);
        dataset.put("data_sha256", "sha-" + sourceKey);
        ObjectNode range = dataset.putObject("time_range");
        range.put("from", START.toString());
        range.put("to", START.plusDays(values.length - 1).toString());
        var rows = dataset.putArray("rows");
        for (int index = 0; index < values.length; index++) {
            var row = JsonNodeFactory.instance.arrayNode();
            row.add(START.plusDays(index).toString());
            row.add(values[index]);
            rows.add(row);
        }
        return dataset;
    }

    private static double[] rising(int count) {
        double[] out = new double[count];
        for (int index = 0; index < count; index++) {
            out[index] = 100 + index * 3;
        }
        return out;
    }

    private static double[] falling(int count) {
        double[] out = new double[count];
        for (int index = 0; index < count; index++) {
            out[index] = 133 - index * 3;
        }
        return out;
    }

    @Test
    @DisplayName("多点同向抬升判持续上行，并把算式一并落库")
    void risingSeriesIsSustainedUp() {
        ObjectNode trend = TrendAnalysis.analyze(dataset("sample-up", rising(12)));

        assertEquals("trend/1", trend.path("contract").asString(""));
        assertEquals(TrendAnalysis.SUSTAINED_UP, trend.path("verdict").asString(""));
        assertEquals("持续上行", trend.path("label").asString(""));
        assertTrue(TrendAnalysis.directional(trend));
        assertEquals(12, trend.path("points").asInt());
        assertEquals("2026-01-01", trend.path("period").path("from").asString(""));
        assertEquals("2026-01-12", trend.path("period").path("to").asString(""));
        assertEquals(100.0, trend.path("first").path("value").asDouble(), 1e-6);
        assertEquals(133.0, trend.path("last").path("value").asDouble(), 1e-6);
        assertEquals(33.0, trend.path("net_change_pct").asDouble(), 1e-6);
        assertEquals(1.0, trend.path("sign_agreement").asDouble(), 1e-6);
        assertEquals(3, trend.path("segments").size());
        assertEquals(8, trend.path("thresholds").path("min_points").asInt());
        assertEquals(60.0, trend.path("thresholds").path("high_volatility_pct").asDouble(), 1e-6);
        assertEquals("single_source", trend.path("corroboration").asString(""));
        assertEquals("primary", trend.path("evidence").get(0).path("role").asString(""));
        assertEquals("sha-sample-up", trend.path("evidence").get(0).path("data_sha256").asString(""));
        assertTrue(trend.path("speculation_boundary").asString("").contains("推测"),
            "推测边界必须随判定一起落库，不能让下游自己写");
        assertTrue(TrendAnalysis.summary(trend).contains("持续上行"));
    }

    @Test
    @DisplayName("多点同向下行判持续下行")
    void fallingSeriesIsSustainedDown() {
        ObjectNode trend = TrendAnalysis.analyze(dataset("sample-down", falling(12)));

        assertEquals(TrendAnalysis.SUSTAINED_DOWN, trend.path("verdict").asString(""));
        assertEquals("持续下行", trend.path("label").asString(""));
        assertTrue(TrendAnalysis.directional(trend));
        assertTrue(trend.path("net_change_pct").asDouble() <= -10.0);
        assertTrue(trend.path("median_shift_pct").asDouble() <= -5.0);
    }

    @Test
    @DisplayName("来回震荡只能算区间波动，不许给方向")
    void oscillatingSeriesIsRange() {
        double[] values = {100, 160, 100, 160, 100, 160, 100, 160, 100, 160, 100, 160};
        ObjectNode trend = TrendAnalysis.analyze(dataset("sample-range", values));

        assertEquals(TrendAnalysis.RANGE, trend.path("verdict").asString(""));
        assertEquals("区间波动", trend.path("label").asString(""));
        assertFalse(TrendAnalysis.directional(trend));
    }

    @Test
    @DisplayName("波动主导时即使首末有净变化也不判趋势")
    void highVolatilityOverridesNetChange() {
        double[] values = {100, 100, 100, 100, 320, 100, 100, 100, 100, 100, 100, 340};
        ObjectNode trend = TrendAnalysis.analyze(dataset("sample-volatile", values));

        assertEquals(TrendAnalysis.RANGE, trend.path("verdict").asString(""));
        assertTrue(trend.path("volatility_pct").asDouble() >= 60.0,
            "波动率超过阈值就该被拦下，实际 " + trend.path("volatility_pct").asDouble());
    }

    @Test
    @DisplayName("少于八期只给样本不足，不留方向字段")
    void shortSeriesIsInsufficient() {
        ObjectNode trend = TrendAnalysis.analyze(dataset("sample-short", 100, 120, 140, 160, 180));

        assertEquals(TrendAnalysis.INSUFFICIENT, trend.path("verdict").asString(""));
        assertEquals("样本不足", trend.path("label").asString(""));
        assertFalse(TrendAnalysis.directional(trend));
        assertEquals(0, trend.path("segments").size());
        assertTrue(trend.path("limits").toString().contains("少于 8 期"));
        assertFalse(trend.has("median_shift_pct"), "样本不足不许给出位移百分比");
    }

    @Test
    @DisplayName("没有 rows 的快照（点状形态）判样本不足，不抛异常")
    void nonSeriesSnapshotIsInsufficient() {
        ObjectNode dataset = dataset("sample-point");
        dataset.remove("rows");

        ObjectNode trend = TrendAnalysis.analyze(dataset);
        assertEquals(TrendAnalysis.INSUFFICIENT, trend.path("verdict").asString(""));
        assertEquals(0, trend.path("points").asInt());
    }

    @Test
    @DisplayName("多来源同向记 consistent，反向记 divergent 并写明分歧")
    void crossSourceDirectionDecidesCorroboration() {
        ObjectNode primary = dataset("sample-up", rising(12));
        ObjectNode sameSide = dataset("other-up", rising(13));
        ObjectNode opposite = dataset("other-down", falling(12));

        ObjectNode consistent = TrendAnalysis.analyze(primary, List.of(sameSide));
        assertEquals("consistent", consistent.path("corroboration").asString(""));
        assertEquals(2, consistent.path("evidence").size());
        assertEquals("corroborating", consistent.path("evidence").get(1).path("role").asString(""));

        ObjectNode divergent = TrendAnalysis.analyze(primary, List.of(opposite));
        assertEquals("divergent", divergent.path("corroboration").asString(""));
        assertTrue(divergent.path("disagreements").toString().contains("other-down"));
        assertTrue(divergent.path("limits").toString().contains("不得给出单一方向"));
        // 分歧不改主判定：判定是主来源的事实陈述
        assertEquals(TrendAnalysis.SUSTAINED_UP, divergent.path("verdict").asString(""));
    }

    @Test
    @DisplayName("交叉来源期数不够也算对不上，并写进分歧")
    void shortCrossSourceIsDivergent() {
        ObjectNode primary = dataset("sample-up", rising(12));
        ObjectNode tooShort = dataset("other-short", 100, 110, 120);

        ObjectNode trend = TrendAnalysis.analyze(primary, List.of(tooShort));
        assertEquals("divergent", trend.path("corroboration").asString(""));
        assertTrue(trend.path("disagreements").toString().contains("3 期"));
    }

    @Test
    @DisplayName("趋势选题：形态、期数、节奏三项缺一不可")
    void opportunityMustBeTrendable() {
        assertTrue(TrendAnalysis.routable(opportunity("wide", 12, "daily")));
        assertFalse(TrendAnalysis.routable(opportunity("rows", 12, "daily")));
        assertFalse(TrendAnalysis.routable(opportunity("wide", 5, "daily")));
        assertFalse(TrendAnalysis.routable(opportunity("wide", 12, " ")));
        assertFalse(TrendAnalysis.routable(null));

        List<String> violations = TrendAnalysis.opportunityViolations(opportunity("rows", 5, ""));
        assertEquals(3, violations.size());
        assertTrue(violations.toString().contains("wide"));
        assertTrue(violations.toString().contains("少于") || violations.toString().contains("不足"));
        assertTrue(violations.toString().contains("cadence"));
    }

    @Test
    @DisplayName("判定中文名只有一个来源，未知判定退回样本不足")
    void labelFallsBackToInsufficient() {
        assertEquals("持续上行", TrendAnalysis.label(TrendAnalysis.SUSTAINED_UP));
        assertEquals("区间波动", TrendAnalysis.label(TrendAnalysis.RANGE));
        assertEquals("样本不足", TrendAnalysis.label(null));
        assertEquals("样本不足", TrendAnalysis.label("unknown_verdict"));
    }

    @Test
    @DisplayName("提示词摘要必须带上判定、位移与边界")
    void summaryCarriesVerdictAndBoundary() {
        ObjectNode trend = TrendAnalysis.analyze(dataset("sample-up", rising(12)));
        String summary = TrendAnalysis.summary(trend);

        assertTrue(summary.contains("持续上行"));
        assertTrue(summary.contains("整体位移"));
        assertTrue(summary.contains("边界："));
        assertEquals("（未产出趋势判定）", TrendAnalysis.summary(null));
    }

    private static EditorialSelection.Opportunity opportunity(String shape, int points, String cadence) {
        return new EditorialSelection.Opportunity(1L, "sample-trend", "关注指数连续抬升", "连续多期同向",
            points, "sha-sample-trend", shape, cadence, "attention");
    }
}
