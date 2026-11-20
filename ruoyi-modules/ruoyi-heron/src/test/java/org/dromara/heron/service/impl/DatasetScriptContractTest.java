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
            + "{\"lead\":\"较区间起点走高\",\"body\":\"" + secondBody + "\",\"duration_s\":15},"
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
    @DisplayName("真渲染验收稿先过事实与长度契约，避免昂贵配音后才发现样稿违规")
    void renderFixtureMeetsTheRealContract() {
        var dataset = snapshot();
        var script = (tools.jackson.databind.node.ObjectNode) JSON.readTree(DataBoardPipelineRenderTest.SCRIPT);
        script.put("dataset_sha256", dataset.path("data_sha256").asString());
        script.put("source_key", dataset.path("source_key").asString());
        script.put("chart", "line");
        assertEquals(List.of(), DatasetScriptContract.violations(script, dataset));
    }

    @Test
    @DisplayName("旁白出现裸数字直接退回")
    void bareDigitsRejected() {
        List<String> items = DatasetScriptContract.violations(
            JSON.readTree(script(snapshot().path("data_sha256").asString(), "line", "上涨 3%。")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("旁白") && item.contains("裸数字")), items.toString());
    }

    @Test
    @DisplayName("区间首末涨跌不能写成上一日或单日涨跌")
    void rejectsDailyClaimsAboutPeriodChange() {
        for (String body : List.of("较前一日上涨 {{facts.change}} 美元。",
            "较上一交易日涨幅 {{facts.change_pct}}%。")) {
            List<String> items = DatasetScriptContract.violations(JSON.readTree(
                script(snapshot().path("data_sha256").asString(), "line", body)), snapshot());
            assertTrue(items.stream().anyMatch(item -> item.contains("区间变化") && item.contains("单日")),
                items.toString());
        }
    }

    @Test
    @DisplayName("geo 没有渲染模块：映射为空、不可渲染、契约直接拒绝")
    void geoFailsClosed() {
        assertTrue(DatasetScriptContract.chartFor("geo").isEmpty(), "geo 不该再映射到没有实现的 map");
        assertFalse(DatasetScriptContract.renderable("geo"));
        assertTrue(DatasetScriptContract.renderable("wide"));
        assertTrue(DatasetScriptContract.renderable("long"));
        assertTrue(DatasetScriptContract.renderable("rows"));
        tools.jackson.databind.node.ObjectNode geo = JSON.createObjectNode();
        geo.put("shape", "geo");
        List<String> items = DatasetScriptContract.violations(
            JSON.readTree(script(snapshot().path("data_sha256").asString(), "", "上涨 {{facts.change}} 美元。")), geo);
        assertTrue(items.stream().anyMatch(item -> item.contains("geo") && item.contains("没有可渲染")), items.toString());
    }

    @Test
    @DisplayName("未知形态一律不可渲染，不会静默放行")
    void unknownShapeNotRenderable() {
        assertFalse(DatasetScriptContract.renderable(""));
        assertFalse(DatasetScriptContract.renderable(null));
        assertFalse(DatasetScriptContract.renderable("hectare"));
    }

    @Test
    @DisplayName("开场先讲来源口径直接退回")
    void metaOpeningRejected() {
        List<String> items = DatasetScriptContract.violations(JSON.readTree(scriptWithOpening(
            "数据源显示金价在动", "最新收于 {{facts.last.value}} 美元。")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("来源或口径")), items.toString());
    }

    @Test
    @DisplayName("前两屏没有任何数据事实直接退回")
    void earlyScenesWithoutFactsRejected() {
        List<String> items = DatasetScriptContract.violations(JSON.readTree(
            "{\"dataset_sha256\":\"" + snapshot().path("data_sha256").asString()
                + "\",\"source_key\":\"sina-gold-xau\",\"chart\":\"line\","
                + "\"title\":\"金价再创新高\",\"scenes\":["
                + "{\"lead\":\"金价最近很热闹\",\"body\":\"各方都在讨论。\",\"duration_s\":15},"
                + "{\"lead\":\"市场情绪高涨\",\"body\":\"讨论的人越来越多。\",\"duration_s\":15},"
                + "{\"lead\":\"创下阶段高点\",\"body\":\"区间最高 {{facts.max.value}} 美元。\",\"duration_s\":15}],"
                + "\"closing\":\"关注接下来的走势。\",\"source_name\":\"来源：新浪财经\"}"), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("前两屏")), items.toString());
    }

    @Test
    @DisplayName("开场逐字复读标题直接退回")
    void openingRepeatingTitleRejected() {
        List<String> items = DatasetScriptContract.violations(JSON.readTree(scriptWithOpening(
            "金价再创新高", "实际是区间累计涨幅 {{facts.change_pct}}%。")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("复读") || item.contains("逐字重复")),
            items.toString());
    }

    @Test
    @DisplayName("结尾空关注引导直接退回")
    void emptyClosingRejected() {
        String withEmpty = valid().replace("关注接下来的走势。", "记得点个关注，我们下期再见。");
        List<String> items = DatasetScriptContract.violations(JSON.readTree(withEmpty), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("空泛的关注引导")), items.toString());
    }

    @Test
    @DisplayName("只有一段引用事实直接退回")
    void tooFewFactScenesRejected() {
        String thin = valid().replace("上涨 {{facts.change}} 美元，涨幅 {{facts.change_pct}}%。", "这就是重点。")
            .replace("区间最高 {{facts.max.value}} 美元。", "高点也很关键。");
        List<String> items = DatasetScriptContract.violations(JSON.readTree(thin), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("引用数据事实")), items.toString());
    }

    /** 第一段换成给定 lead/body，其余段沿用合规样稿。 */
    private static String scriptWithOpening(String lead, String body) {
        return "{\"dataset_sha256\":\"" + snapshot().path("data_sha256").asString()
            + "\",\"source_key\":\"sina-gold-xau\",\"chart\":\"line\","
            + "\"title\":\"金价再创新高\",\"scenes\":["
            + "{\"lead\":\"" + lead + "\",\"body\":\"" + body + "\",\"duration_s\":15},"
            + "{\"lead\":\"较区间起点走高\",\"body\":\"上涨 {{facts.change}} 美元。\",\"duration_s\":15},"
            + "{\"lead\":\"创下阶段高点\",\"body\":\"区间最高 {{facts.max.value}} 美元。\",\"duration_s\":15}],"
            + "\"closing\":\"关注接下来的走势。\",\"source_name\":\"来源：新浪财经\"}";
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
