package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 趋势稿的占位符：{{facts.*}} 与 {{trend.*}} 共用一套解析。
 *
 * <p>这里最容易出的事故是「稿子按趋势写、上屏却读不到趋势值」——
 * 解析器只认 dataset，{{trend.*}} 就会整条 409，出片直接失败；
 * 反过来如果静默留空，观众看到的就是一句带花括号的旁白。
 */
@DisplayName("趋势稿占位符：facts 与 trend 同源解析")
class DatasetPlaceholdersTrendTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode dataset() {
        ObjectNode dataset = JSON.createObjectNode();
        dataset.put("contract", "dataset/1");
        dataset.put("source_key", "sample-gold");
        dataset.put("data_sha256", "sha-gold");
        ObjectNode metric = dataset.putObject("metric");
        metric.put("key", "gold_close");
        metric.put("label", "金价");
        metric.put("unit", "美元");
        metric.put("precision", 1);
        ObjectNode facts = dataset.putObject("facts");
        facts.putObject("first").put("date", "2026-01-01").put("value", 100.0);
        facts.putObject("last").put("date", "2026-01-12").put("value", 133.0);
        facts.put("change", 33.0);
        facts.put("change_pct", 33.0);
        return dataset;
    }

    private static JsonNode trend() {
        ObjectNode trend = JSON.createObjectNode();
        trend.put("contract", "trend/1");
        trend.put("verdict", "sustained_up");
        trend.put("label", "持续上行");
        trend.put("points", 12);
        trend.put("net_change_pct", 33.0);
        trend.put("median_shift_pct", 21.5);
        trend.put("sign_agreement", 0.91);
        ObjectNode period = trend.putObject("period");
        period.put("from", "2026-01-01");
        period.put("to", "2026-01-12");
        ArrayNode segments = trend.putArray("segments");
        ObjectNode first = segments.addObject();
        first.put("index", 1);
        first.put("from", "2026-01-01");
        first.put("to", "2026-01-04");
        first.put("median", 103.0);
        return trend;
    }

    private static JsonNode script(String body) {
        ObjectNode script = JSON.createObjectNode();
        script.put("dataset_sha256", "sha-gold");
        script.put("source_key", "sample-gold");
        script.put("title", "金价这三个月在往上走");
        ArrayNode scenes = script.putArray("scenes");
        scenes.addObject().put("lead", "先看起点").put("body", body).put("duration_s", 8);
        scenes.addObject().put("lead", "再看终点").put("body", "终点是{{facts.last.value}}（{{facts.last.date}}）")
            .put("duration_s", 8);
        script.put("closing", "变化已经发生，原因仍需更多证据");
        script.put("source_name", "sample-gold");
        return script;
    }

    @Test
    @DisplayName("{{trend.*}} 与 {{facts.*}} 一并换成可朗读文本")
    void resolvesTrendAndFactsTogether() {
        JsonNode resolved = DatasetPlaceholders.resolve(script(
            "从{{trend.period.from}}到{{trend.period.to}}，判定是{{trend.label}}，"
                + "中位数移动了{{trend.median_shift_pct}}"), dataset(), trend());
        String body = resolved.path("scenes").get(0).path("body").asString();
        assertTrue(body.contains("持续上行"), body);
        assertTrue(body.contains("2026-01-01"), body);
        assertTrue(body.contains("2026-01-12"), body);
        assertTrue(body.contains("21.5"), body);
        assertEquals("终点是133.0（2026-01-12）", resolved.path("scenes").get(1).path("body").asString());
    }

    @Test
    @DisplayName("分段取值：segments[0].from 按数组下标解析")
    void resolvesSegmentsByIndex() {
        JsonNode resolved = DatasetPlaceholders.resolve(
            script("第一段是{{trend.segments[0].from}}到{{trend.segments[0].to}}"), dataset(), trend());
        String body = resolved.path("scenes").get(0).path("body").asString();
        assertEquals("第一段是2026-01-01到2026-01-04", body);
        // 点号下标同样要认，两种写法不能只照顾一种
        JsonNode dotted = DatasetPlaceholders.resolve(script("第一段到{{trend.segments.0.to}}"),
            dataset(), trend());
        assertEquals("第一段到2026-01-04", dotted.path("scenes").get(0).path("body").asString());
    }

    @Test
    @DisplayName("迁移到解析器但没传 trend：{{trend.*}} 必须 409，不许留空")
    void trendPlaceholderWithoutTrendFails() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> DatasetPlaceholders.resolve(script("判定是{{trend.label}}"), dataset()));
        assertEquals("dataset_placeholder", error.getCode());
        assertTrue(error.getMessage().contains("trend.label"), error.getMessage());
    }

    @Test
    @DisplayName("趋势节点没有的字段也要 409：样本不足时没有位移值，稿子不能假装有")
    void missingTrendFieldFails() {
        JsonNode insufficient = trend();
        ((ObjectNode) insufficient).remove("median_shift_pct");
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> DatasetPlaceholders.resolve(script("中位数移动了{{trend.median_shift_pct}}"),
                dataset(), insufficient));
        assertEquals("dataset_placeholder", error.getCode());
        assertTrue(error.getMessage().contains("median_shift_pct"), error.getMessage());
    }
}
