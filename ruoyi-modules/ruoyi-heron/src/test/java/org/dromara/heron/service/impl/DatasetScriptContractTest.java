package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 数据看板脚本契约：形状归本闸，数字与占位符复用 DatasetContract。 */
@DisplayName("数据看板：脚本契约")
class DatasetScriptContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode snapshot() {
        String body = "/*x*/ var _hf_XAU=(["
            + "{\"date\":\"2026-11-05\",\"close\":\"4091.55\"},"
            + "{\"date\":\"2026-11-06\",\"close\":\"4105.50\"},"
            + "{\"date\":\"2026-11-09\",\"close\":\"4110.04\"},"
            + "{\"date\":\"2026-11-10\",\"close\":\"4113.34\"}]);";
        return JSON.readTree(SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD, body,
            Instant.parse("2026-11-10T03:22:00Z"), 30));
    }

    private static String script(String sha, String chart, String secondBody) {
        return "{\"dataset_sha256\":\"" + sha + "\",\"source_key\":\"sina-gold-xau\",\"chart\":\"" + chart + "\","
            + "\"title\":\"金价再创新高\",\"scenes\":["
            + "{\"lead\":\"金价站上关键位\",\"body\":\"最新收于 {{facts.last.value}} 美元。\",\"duration_s\":15},"
            + "{\"lead\":\"较前一日走高\",\"body\":\"" + secondBody + "\",\"duration_s\":15},"
            + "{\"lead\":\"创下阶段高点\",\"body\":\"区间最高 {{facts.max.value}} 美元。\",\"duration_s\":15}],"
            + "\"closing\":\"关注接下来的走势。\",\"source_name\":\"来源：新浪财经\"}";
    }

    private static String valid() {
        return script(snapshot().path("data_sha256").asString(), "line",
            "上涨 {{facts.change}} 美元，涨幅 {{facts.change_pct}}%。");
    }

    @Test
    @DisplayName("合规脚本没有违例")
    void compliantPasses() {
        assertEquals(List.of(), DatasetScriptContract.violations(JSON.readTree(valid()), snapshot()));
    }

    @Test
    @DisplayName("旁白出现裸数字直接退回")
    void bareDigitsRejected() {
        List<String> items = DatasetScriptContract.violations(
            JSON.readTree(script(snapshot().path("data_sha256").asString(), "line", "上涨 3%。")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("旁白") && item.contains("裸数字")), items.toString());
    }

    @Test
    @DisplayName("哈希、图表类型、未知字段与段落数都拒绝")
    void rejectsShapeDrift() {
        String sha = snapshot().path("data_sha256").asString();
        assertTrue(DatasetScriptContract.violations(
            JSON.readTree(script("0".repeat(64), "line", "上涨 {{facts.change}} 美元。")), snapshot()).stream()
            .anyMatch(item -> item.contains("dataset_sha256")));
        assertTrue(DatasetScriptContract.violations(
            JSON.readTree(script(sha, "race", "上涨 {{facts.change}} 美元。")), snapshot()).stream()
            .anyMatch(item -> item.contains("chart")));
        String withExtra = valid().replace("\"closing\"", "\"extra\":1,\"closing\"");
        assertTrue(DatasetScriptContract.violations(JSON.readTree(withExtra), snapshot()).stream()
            .anyMatch(item -> item.contains("未知字段 extra")));
    }
}
