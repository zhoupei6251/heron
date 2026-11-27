package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.lane.databoard.Column;
import org.dromara.heron.service.impl.lane.databoard.DatasetSignal;
import org.dromara.heron.service.impl.shared.research.DataOpportunityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 机会去重键与信号落库形状：这两处错了会静默产生重复行或丢信号。 */
@DisplayName("数据机会：去重键与信号序列化")
class DataOpportunityServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static DatasetSignal.Signal signal(String entity, int window) {
        return signal(entity, window, "price");
    }

    private static DatasetSignal.Signal signal(String entity, int window, String metric) {
        return new DatasetSignal.Signal("wide", entity, metric, "2026-01-01", "2026-04-30",
            120, window, 0.8, 0.042, 2.7, true, 1.0, "zscore", 0.01, 0.05,
            java.util.List.of("近 7 期 +4.2%"));
    }

    @Test
    @DisplayName("去重键：同源同信号同日稳定，换日或换窗口就不同")
    void dedupKeyScope() {
        String base = DataOpportunityService.dedupKey("sina-gold-xau", signal("", 120), LocalDate.of(2026, 11, 13));

        assertEquals(base, DataOpportunityService.dedupKey("sina-gold-xau", signal("", 120), LocalDate.of(2026, 11, 13)));
        assertEquals(64, base.length(), "去重键是 sha256 hex");
        assertNotEquals(base, DataOpportunityService.dedupKey("sina-gold-xau", signal("", 120),
            LocalDate.of(2026, 11, 14)), "跨日期应当是另一条机会");
        assertNotEquals(base, DataOpportunityService.dedupKey("sina-gold-xau", signal("", 60),
            LocalDate.of(2026, 11, 13)), "换窗口是另一套口径");
        assertNotEquals(base, DataOpportunityService.dedupKey("sina-gold-xau", signal("黄金", 120),
            LocalDate.of(2026, 11, 13)), "换实体是另一条机会");
    }

    @Test
    @DisplayName("信号落库：数值与标记都在，别在序列化时丢掉")
    void signalsJsonKeepsNumbers() {
        String json = DataOpportunityService.signalsJson(signal("", 120), java.util.List.of());
        var node = JSON.readTree(json);

        assertEquals("zscore", node.path("signal_type").asString(""));
        assertEquals(0.042, node.path("change_pct").asDouble(), 1e-9);
        assertEquals(2.7, node.path("zscore").asDouble(), 1e-9);
        assertTrue(node.path("extreme").asBoolean());
        assertEquals(120, node.path("window_points").asInt());
        assertEquals(1, node.path("flags").size());
        assertEquals(1, node.path("merged_count").asInt(), "没合并时就是自己一个");
    }

    @Test
    @DisplayName("快照落库：元信息与尾部点都留，超长时宁可不全也不写大字段")
    void snapshotForStoreKeepsTailAndMetadata() {
        var small = JSON.readTree("""
            {"contract":"dataset/1","shape":"wide","cadence":"daily","retrieved_at":"2026-11-13T00:00:00Z",
             "data_sha256":"abc","rows":[{"date":"2026-11-12","value":4.6},{"date":"2026-11-13","value":4.58}]}
            """);
        var stored = JSON.readTree(DataOpportunityService.snapshotForStore(small));

        assertEquals("dataset/1", stored.path("contract").asString(""));
        assertEquals("abc", stored.path("data_sha256").asString(""), "上游摘要要留住，用来确认是同一份数据");
        assertEquals(2, stored.path("source_points").asInt());
        assertFalse(stored.path("truncated").asBoolean(true), "点数没超上限不该标记截断");
        assertEquals(2, stored.path("points").size());

        StringBuilder many = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < DataOpportunityService.SNAPSHOT_POINTS + 20; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"date\":\"d").append(i).append("\",\"value\":").append(i).append('}');
        }
        many.append("]}");
        var capped = JSON.readTree(DataOpportunityService.snapshotForStore(JSON.readTree(many.toString())));

        assertEquals(DataOpportunityService.SNAPSHOT_POINTS, capped.path("points").size(), "只留尾巴");
        assertTrue(capped.path("truncated").asBoolean(false), "截断要留标记，别让人误以为看到的是全部");
        assertEquals(DataOpportunityService.SNAPSHOT_POINTS + 20, capped.path("source_points").asInt());
    }

    private static DataOpportunityService.Candidate candidate(String sourceKey, String unit,
                                                              double changePct, double score) {
        return candidate(sourceKey, unit, changePct, score, "price");
    }

    private static DataOpportunityService.Candidate candidate(String sourceKey, String unit,
                                                              double changePct, double score, String metric) {
        DatasetSignal.Signal base = signal("", 120, metric);
        DatasetSignal.Signal adjusted = new DatasetSignal.Signal(base.shape(), base.entity(), base.metric(),
            base.periodStart(), base.periodEnd(), base.points(), base.windowPoints(), base.magnitude(),
            changePct, base.zscore(), base.extreme(), base.coverage(), base.signalType(),
            base.absChange(), base.changeScale(), base.flags());
        return new DataOpportunityService.Candidate(sourceKey, "来源" + sourceKey, "daily", unit,
            Column.MARKET, adjusted, score, "标题 " + sourceKey, null);
    }

    @Test
    @DisplayName("同一量的多个入口合并：同实体同指标同向只留最强一条，其余源留痕")
    void mergesSameQuantityFromMultipleFeeds() {
        var groups = DataOpportunityService.merge(java.util.List.of(
            candidate("usgs-quake-m45", "次", -0.0300, 61.96),
            candidate("usgs-quake-m50", "次", -0.0295, 59.01),
            candidate("usgs-quake-m60", "次", -0.0294, 51.27)));

        assertEquals(1, groups.size(), "同实体、同指标、同单位、同向、同窗口 → 同一份计数");
        assertEquals("usgs-quake-m45", groups.get(0).lead().sourceKey(), "留分数最高的一条");
        assertEquals(java.util.List.of("usgs-quake-m50", "usgs-quake-m60"), groups.get(0).members());
        assertEquals(3, groups.get(0).count());
    }

    @Test
    @DisplayName("不同货币对不合并：欧元跌不能当澳元跌的佐证")
    void doesNotMergeDifferentPairs() {
        var groups = DataOpportunityService.merge(java.util.List.of(
            candidate("frankfurter-audcny", "CNY", -0.0239, 61.96, "aud_cny"),
            candidate("frankfurter-jpycny", "CNY", -0.0095, 59.01, "jpy_cny"),
            candidate("frankfurter-eurcny", "CNY", -0.0094, 51.27, "eur_cny")));

        assertEquals(3, groups.size(), "换了实体/指标就是另一条机会，不能并成「同向 3 个源」");
    }

    @Test
    @DisplayName("方向相反不合并：涨和跌是两件事")
    void doesNotMergeOppositeDirection() {
        var groups = DataOpportunityService.merge(java.util.List.of(
            candidate("sina-gold-xau", "USD/oz", -0.03, 55),
            candidate("sina-oil-cl", "USD/oz", 0.04, 52)));

        assertEquals(2, groups.size());
    }

    @Test
    @DisplayName("单位不同不合并：金价和油价不是一个量")
    void doesNotMergeDifferentUnits() {
        var groups = DataOpportunityService.merge(java.util.List.of(
            candidate("sina-gold-xau", "USD/oz", -0.03, 55),
            candidate("sina-oil-cl", "USD/bbl", -0.04, 52)));

        assertEquals(2, groups.size());
    }

    @Test
    @DisplayName("合并在标题里点明同向源数，且不超过长度上限")
    void headlineMentionsMergedCount() {
        assertEquals("标题", DataOpportunityService.headlineWithMerged("标题", 1));
        String merged = DataOpportunityService.headlineWithMerged("标题", 5);
        assertTrue(merged.contains("5 个源"), merged);

        String longHeadline = "标".repeat(250);
        assertTrue(DataOpportunityService.headlineWithMerged(longHeadline, 5).length() <= 200,
            "加后缀不能撑破 200 字上限");
    }
}
