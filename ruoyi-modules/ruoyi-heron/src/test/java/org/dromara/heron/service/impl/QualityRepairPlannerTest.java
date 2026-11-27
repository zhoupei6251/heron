package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.quality.QualityRepairPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 定向修复：只有白名单里的判据能动，改完必须整链重跑。 */
@DisplayName("L3 审稿：定向修复白名单")
class QualityRepairPlannerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode report(String id, String verdict, boolean repairable) {
        return JSON.readTree("""
            {"criteria":[{"id":"%s","verdict":"%s","class":"subjective","confidence":"medium",
             "machineVerifiable":false,"severity":"minor","repairable":%s,
             "evidence":[{"locator":"focus[scene1].mode","quote":"latest"}],"reason":"测试"}]}
            """.formatted(id, verdict, repairable));
    }

    @Test
    @DisplayName("正式成片不修：改稿要走重生产，不是给已完成的版本打补丁")
    void finalModeIsNotRepaired() {
        assertTrue(QualityRepairPlanner.plan(report("hook_3s", "fail", true), "final").isEmpty());
    }

    @Test
    @DisplayName("草稿模式只修白名单里的判据")
    void onlyWhitelistedCriteriaAreRepaired() {
        Optional<QualityRepairPlanner.RepairPlan> plan =
            QualityRepairPlanner.plan(report("hook_3s", "fail", true), "draft");

        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().actions().size());
        assertEquals("hook_3s", plan.get().actions().get(0).criterionId());
        assertEquals("scenes[0].lead", plan.get().actions().get(0).field());
    }

    @Test
    @DisplayName("白名单外的判据即使模型说可修也不修")
    void outsideWhitelistIsNotRepaired() {
        assertTrue(QualityRepairPlanner.plan(report("peak_focus", "fail", true), "draft").isEmpty());
        assertFalse(QualityRepairPlanner.repairable("peak_focus", report("peak_focus", "fail", true)
            .path("criteria").get(0)));
    }

    @Test
    @DisplayName("通过的判据没有可修之处")
    void passingCriterionIsNotRepairable() {
        assertTrue(QualityRepairPlanner.plan(report("hook_3s", "pass", true), "draft").isEmpty());
    }

    @Test
    @DisplayName("修复提示只写改哪里、保留什么")
    void promptStatesBoundaries() {
        QualityRepairPlanner.RepairPlan plan = QualityRepairPlanner.plan(
            report("hook_3s", "fail", true), "draft").orElseThrow();

        String prompt = QualityRepairPlanner.prompt(plan, JSON.readTree("{\"title\":\"标题\"}"));

        assertTrue(prompt.contains("hook_3s"), prompt);
        assertTrue(prompt.contains("事实引用"), prompt);
        assertFalse(prompt.contains("豁免"), prompt);
        assertFalse(prompt.contains("放宽"), prompt);
    }

    @Test
    @DisplayName("修复后必须重跑的技术检查清单")
    void recheckStepsCoverTheWholeChain() {
        var steps = QualityRepairPlanner.recheckSteps();

        assertTrue(steps.size() >= 9, steps.toString());
        assertTrue(steps.stream().anyMatch(step -> step.contains("重新配音")), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> step.contains("字幕")), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> step.contains("闸门")), steps.toString());
    }

    @Test
    @DisplayName("修复预算只有一次")
    void repairBudgetIsOne() {
        assertEquals(1, QualityRepairPlanner.MAX_ATTEMPTS);
        assertEquals(1, QualityRepairPlanner.emptyRepair().path("max").asInt());
        assertEquals(0, QualityRepairPlanner.emptyRepair().path("attempted").asInt());
    }
}
