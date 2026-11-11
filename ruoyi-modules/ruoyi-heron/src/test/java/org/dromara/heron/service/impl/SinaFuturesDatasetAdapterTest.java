package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 新浪日线 → dataset/1：只验解析、facts 与哈希口径，不碰网络。 */
@DisplayName("数据看板：新浪期货日线适配器")
class SinaFuturesDatasetAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-10T03:22:00Z");

    private static String row(String date, String close) {
        return "{\"date\":\"" + date + "\",\"open\":\"0\",\"high\":\"0\",\"low\":\"0\",\"close\":\"" + close + "\"}";
    }

    private static String jsonp(String... rows) {
        return "/*<script>location.href='//sina.com';</script>*/ var _hf_XAU=([" + String.join(",", rows) + "]);";
    }

    private static String body() {
        return jsonp(row("2026-11-05", "4091.55"), row("2026-11-06", "4105.50"),
            row("2026-11-09", "4110.04"), row("2026-11-10", "4113.34"));
    }

    private static JsonNode snapshot(String body, int limit) {
        return JSON.readTree(SinaFuturesDatasetAdapter.build(
            SinaFuturesDatasetAdapter.GOLD, body, NOW, limit));
    }

    @Test
    @DisplayName("宽表快照：列、时间窗、facts 与哈希齐全")
    void buildsWideSnapshot() {
        JsonNode node = snapshot(body(), 30);
        assertEquals("dataset/1", node.path("contract").asString());
        assertEquals("sina-gold-xau", node.path("source_key").asString());
        assertEquals("wide", node.path("shape").asString());
        assertEquals("daily", node.path("cadence").asString());
        assertEquals(4, node.path("rows").size());
        assertEquals("2026-11-05", node.path("time_range").path("start").asString());
        assertEquals("2026-11-10", node.path("time_range").path("end").asString());
        assertEquals(4, node.path("time_range").path("points").asInt());

        JsonNode facts = node.path("facts");
        assertEquals(21.79, facts.path("change").asDouble(), 0.0001);
        assertEquals(0.53, facts.path("change_pct").asDouble(), 0.0001);
        assertEquals(4091.55, facts.path("min").path("value").asDouble(), 0.0001);
        assertEquals(4113.34, facts.path("max").path("value").asDouble(), 0.0001);
        assertEquals(2026, facts.path("period").path("end_year").asInt());

        assertTrue(node.path("data_sha256").asString().matches("[0-9a-f]{64}"));
        assertEquals("来源：新浪财经", node.path("license").path("attribution").asString());
    }

    @Test
    @DisplayName("哈希不含抓取时间：同一份日线两次抓取哈希相同")
    void hashIgnoresRetrievalTime() {
        String first = SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD, body(), NOW, 30);
        String second = SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD, body(),
            Instant.parse("2026-11-11T09:00:00Z"), 30);
        assertEquals(JSON.readTree(first).path("data_sha256").asString(),
            JSON.readTree(second).path("data_sha256").asString());
    }

    @Test
    @DisplayName("limit 只保留最近窗口，起点随之移动")
    void limitKeepsLatestWindow() {
        JsonNode node = snapshot(body(), 2);
        assertEquals(2, node.path("rows").size());
        assertEquals("2026-11-09", node.path("time_range").path("start").asString());
        assertEquals(4110.04, node.path("facts").path("first").path("value").asDouble(), 0.0001);
    }

    @Test
    @DisplayName("乱序与重复日期先排序去重，同日以后出现的值为准")
    void sortsAndDeduplicatesDates() {
        String body = jsonp(row("2026-11-09", "4110.04"), row("2026-11-06", "4105.50"),
            row("2026-11-09", "4110.99"), row("2026-11-10", "4113.34"));
        JsonNode node = snapshot(body, 30);
        assertEquals(3, node.path("rows").size());
        assertEquals("2026-11-06", node.path("rows").get(0).get(0).asString());
        assertEquals(4110.99, node.path("rows").get(1).get(1).asDouble(), 0.0001);
    }

    @Test
    @DisplayName("不足两行或没有数组直接失败，不做兜底")
    void rejectsInsufficientData() {
        String single = jsonp(row("2026-11-10", "4113.34"));
        assertThrows(HeronBusinessException.class, () -> SinaFuturesDatasetAdapter.build(
            SinaFuturesDatasetAdapter.GOLD, single, NOW, 30));
        assertThrows(HeronBusinessException.class, () -> SinaFuturesDatasetAdapter.build(
            SinaFuturesDatasetAdapter.GOLD, "<html>blocked</html>", NOW, 30));
    }
}
