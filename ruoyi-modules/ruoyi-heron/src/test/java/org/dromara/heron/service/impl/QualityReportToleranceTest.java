package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告契约的容错边界。
 *
 * <p>真实故障：解说线的一条稿子让 {@code early_payoff} 判为 fail，机器给它标了「可修」，
 * 而它不在定向修复白名单里——整份报告因此被契约拒掉，人看不到任何审稿结论。
 * 白名单外的「可修」只降级这一条，不该作废整份报告。
 */
@DisplayName("L3 审稿：报告契约的容错边界")
class QualityReportToleranceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final long VERSION_ID = 1900000000000000001L;

    /** 造一条合规的客观判据条目。 */
    private static JsonNode criterion(String id, String verdict, boolean repairable) {
        return JSON.readTree("""
            {"id":"%s","verdict":"%s","class":"objective","confidence":"high","machineVerifiable":true,
             "severity":"major","repairable":%s,
             "evidence":[{"locator":"script.scenes[0].lead","quote":"开场一句"}],"reason":"测试条目"}
            """.formatted(id, verdict, repairable));
    }

    /** 十条判据齐全的报告，越界的「可修」放在 early_payoff 上。 */
    private static tools.jackson.databind.node.ObjectNode report() {
        var root = JSON.createObjectNode();
        var array = JSON.createArrayNode();
        for (String id : QualityCriteria.IDS) {
            boolean repairable = "early_payoff".equals(id) || "hook_3s".equals(id);
            array.add(criterion(id, "fail", repairable));
        }
        root.set("criteria", array);
        root.put("schemaVersion", "quality-review/1");
        root.put("versionId", VERSION_ID);
        root.put("mode", "final");
        root.put("reviewedAt", "2026-11-20T00:00:00");
        root.put("promptVersion", "quality-review-v1");
        root.put("modelId", "test-model");
        root.set("summary", JSON.createObjectNode());
        root.set("repair", JSON.createObjectNode());
        root.set("screenshot", JSON.createObjectNode());
        root.set("notes", JSON.createArrayNode());
        return root;
    }

    private static QualityCriteria.Context context() {
        JsonNode script = JSON.readTree("""
            {"title":"测试稿","closing":"结尾一句",
             "scenes":[{"lead":"开场一句","body":"正文一","duration_s":10}]}
            """);
        return new QualityCriteria.Context(VERSION_ID, "final", script, null,
            JSON.createArrayNode(), null, new java.util.LinkedHashMap<>());
    }

    @Test
    @DisplayName("白名单外的「可修」只降级这一条，报告照常通过")
    void outOfWhitelistRepairableIsDowngraded() {
        JsonNode out = QualityReportContract.validate(report(), context());

        assertEquals(10, out.path("criteria").size(), "十条判据一条都不能少");
        for (JsonNode item : out.path("criteria")) {
            if ("early_payoff".equals(item.path("id").asString())) {
                assertFalse(item.path("repairable").asBoolean(),
                    "白名单外的判据不该标成可修，但结论要留着");
                assertEquals("fail", item.path("verdict").asString());
            }
        }
    }

    @Test
    @DisplayName("白名单内的「可修」保留")
    void whitelistedRepairableIsKept() {
        JsonNode out = QualityReportContract.validate(report(), context());

        for (JsonNode item : out.path("criteria")) {
            if ("hook_3s".equals(item.path("id").asString())) {
                assertTrue(item.path("repairable").asBoolean(), "白名单内的判据该保留可修标记");
            }
        }
    }

    @Test
    @DisplayName("机器判据不再标白名单外的「可修」")
    void machineCriteriaDoNotClaimRepairOutsideWhitelist() {
        assertTrue(QualityRepairPlanner.whitelistIds().contains("hook_3s"));
        assertFalse(QualityRepairPlanner.whitelistIds().contains("early_payoff"),
            "early_payoff 不在定向修复白名单里");
        assertFalse(QualityRepairPlanner.whitelistIds().contains("visual_sync"));
        assertFalse(QualityRepairPlanner.whitelistIds().contains("style_consistency"));
    }
}
