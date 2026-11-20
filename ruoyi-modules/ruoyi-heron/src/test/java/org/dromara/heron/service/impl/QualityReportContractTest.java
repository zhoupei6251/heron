package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 质量报告的结构契约：三类结论不能混，汇总只分类计数。 */
@DisplayName("L3 审稿：报告契约")
class QualityReportContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode dataset() {
        return JSON.readTree("""
            {"facts": {"last": {"date": "2026-11-10", "value": 120.0},
              "change": 20.0, "change_pct": 20.0,
              "max": {"date": "2026-11-10", "value": 120.0}}}
            """);
    }

    private static JsonNode script() {
        return JSON.readTree("""
            {"title": "金价创新高", "chart": "line",
             "scenes": [{"lead": "金价站上关键位", "body": "最新收于 {{facts.last.value}} 美元。", "duration_s": 10}],
             "closing": "下周值得盯住这个位置。"}
            """);
    }

    private static JsonNode checks() {
        return JSON.readTree("""
            [{"gate": "visual", "passed": true, "reason": "版式通过",
              "measured": {"blankFrames": 0, "checkErrors": 0, "checkWarnings": 0}}]
            """);
    }

    private static QualityCriteria.Context ctx() {
        Map<Integer, DatasetVisualFocus.Focus> focus = new LinkedHashMap<>();
        focus.put(1, DatasetVisualFocus.forScene(script().path("scenes").get(0), dataset()));
        return new QualityCriteria.Context(1900000000000000001L, "final", script(), dataset(), checks(), null, focus);
    }

    private static ObjectNode report() {
        ObjectNode report = QualityReportContract.skeleton(1900000000000000001L, "final", "test-model");
        report.set("criteria", QualityReportContract.criteriaArray(java.util.List.of(
            entry("hook_3s", "pass", "objective", "high", true, "none", false,
                "script.scenes[0].lead", "金价站上关键位"),
            entry("early_payoff", "pass", "objective", "high", true, "none", false,
                "script.scenes[0].lead", "金价站上关键位"),
            entry("mid_progression", "pass", "objective", "high", true, "none", false,
                "script.scenes[0].lead", "金价站上关键位"),
            entry("peak_focus", "pass", "subjective", "medium", false, "none", false,
                "focus[scene1].mode", "latest"),
            entry("visual_sync", "pass", "objective", "high", true, "none", false,
                "focus[scene1].refs", "last.value"),
            entry("style_consistency", "pass", "objective", "high", true, "none", false,
                "checks[visual].measured.checkErrors", "0"),
            entry("pacing_variation", "minor", "subjective", "medium", false, "minor", true,
                "focus[scene1].mode", "latest"),
            entry("no_redundancy", "pass", "objective", "high", true, "none", false,
                "script.scenes[0].lead", "金价站上关键位"),
            entry("closing_value", "pass", "objective", "high", true, "none", false,
                "script.closing", "下周值得盯住这个位置。")
        )));
        return report;
    }

    private static ObjectNode entry(String id, String verdict, String cls, String confidence,
                                    boolean machineVerifiable, String severity, boolean repairable,
                                    String locator, String quote) {
        ObjectNode item = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        item.put("id", id);
        item.put("verdict", verdict);
        item.put("class", cls);
        item.put("confidence", confidence);
        item.put("machineVerifiable", machineVerifiable);
        item.put("severity", severity);
        item.put("repairable", repairable);
        ObjectNode evidence = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        evidence.put("locator", locator);
        evidence.put("quote", quote);
        item.set("evidence", tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(evidence));
        item.put("reason", "测试用结论");
        return item;
    }

    @Test
    @DisplayName("合规报告通过校验，汇总按分类计数")
    void compliantReportPasses() {
        ObjectNode validated = QualityReportContract.validate(report(), ctx());
        JsonNode summary = validated.path("summary");
        assertEquals(8, summary.path("pass").asInt());
        assertEquals(1, summary.path("minor").asInt());
        assertEquals(0, summary.path("fail").asInt());
        assertEquals(7, summary.path("objectiveVerified").asInt());
        assertEquals(2, summary.path("subjectiveVerified").asInt());
    }

    @Test
    @DisplayName("汇总里出现总分一律拒绝")
    void totalScoreRejected() {
        ObjectNode report = report();
        ((ObjectNode) report.path("summary")).put("totalScore", 88);
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> QualityReportContract.validate(report, ctx()));
        assertTrue(error.getMessage().contains("未知字段"), error.getMessage());
    }

    @Test
    @DisplayName("主观条目不得声称机器可复核")
    void subjectiveCannotBeMachineVerifiable() {
        ObjectNode report = report();
        ((ObjectNode) report.path("criteria").get(3)).put("machineVerifiable", true);
        assertThrows(HeronBusinessException.class, () -> QualityReportContract.validate(report, ctx()));
    }

    @Test
    @DisplayName("主观意见不得升级成 blocker")
    void subjectiveCannotBlocker() {
        ObjectNode report = report();
        ((ObjectNode) report.path("criteria").get(3)).put("severity", "blocker");
        assertThrows(HeronBusinessException.class, () -> QualityReportContract.validate(report, ctx()));
    }

    @Test
    @DisplayName("客观条目必须高置信且机器可复核")
    void objectiveMustBeHighConfidence() {
        ObjectNode report = report();
        ((ObjectNode) report.path("criteria").get(0)).put("confidence", "medium");
        assertThrows(HeronBusinessException.class, () -> QualityReportContract.validate(report, ctx()));
    }

    @Test
    @DisplayName("九条判据缺一条就拒收")
    void missingCriterionRejected() {
        ObjectNode report = report();
        var array = (tools.jackson.databind.node.ArrayNode) report.path("criteria");
        array.remove(0);
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> QualityReportContract.validate(report, ctx()));
        assertTrue(error.getMessage().contains("criteria"), error.getMessage());
    }

    @Test
    @DisplayName("定位符解析不到时整条降级为 unknown")
    void unresolvableLocatorDowngradesToUnknown() {
        ObjectNode report = report();
        ((ObjectNode) report.path("criteria").get(0).path("evidence").get(0)).put("locator",
            "script.scenes[9].lead");
        ObjectNode validated = QualityReportContract.validate(report, ctx());
        assertEquals("unknown", validated.path("criteria").get(0).path("verdict").asString());
        assertEquals(1, validated.path("summary").path("unknown").asInt());
    }

    @Test
    @DisplayName("白名单外的判据不得声称可修复")
    void repairOutsideWhitelistRejected() {
        ObjectNode report = report();
        ((ObjectNode) report.path("criteria").get(3)).put("repairable", true);
        assertThrows(HeronBusinessException.class, () -> QualityReportContract.validate(report, ctx()));
    }

    @Test
    @DisplayName("只有证据支持定位符的判据能解析")
    void locatorResolution() {
        var ctx = ctx();
        assertTrue(QualityReportContract.resolve("script.scenes[0].lead", ctx));
        assertTrue(QualityReportContract.resolve("checks[visual].measured.checkErrors", ctx));
        assertTrue(QualityReportContract.resolve("focus[scene1].mode", ctx));
        assertFalse(QualityReportContract.resolve("script.scenes[3].lead", ctx));
        assertFalse(QualityReportContract.resolve("checks[audio].measured.nope", ctx));
        assertFalse(QualityReportContract.resolve("nonsense", ctx));
    }
}
