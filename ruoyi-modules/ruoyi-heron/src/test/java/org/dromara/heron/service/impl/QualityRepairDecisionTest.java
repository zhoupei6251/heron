package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.quality.QualityRepairPlanner;
import org.dromara.heron.service.impl.shared.quality.QualityRepairService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修复执行环的判定层：计划怎么裁、稿子怎么抄、复检怎么算过。
 *
 * <p>这三件事都不碰数据库与模型，所以能钉死在单测里：模型改了白名单之外的字，
 * 必须抄不回来；点名的判据缺一条都不算过。
 */
@DisplayName("审稿修复：计划、抄稿与复检判定")
class QualityRepairDecisionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String REPORT = """
        {"criteria":[
          {"id":"hook_3s","verdict":"minor","repairable":true},
          {"id":"closing_value","verdict":"fail","repairable":true},
          {"id":"pacing_variation","verdict":"minor","repairable":true},
          {"id":"copyright_risk","verdict":"fail","repairable":false}
        ],"repair":{"attempted":0,"max":1,"changedSegments":[],"rechecked":false}}
        """;

    private static final String ORIGINAL = """
        {"title":"原标题","dataset_sha256":"abc","closing":"原结尾",
         "scenes":[
           {"lead":"原开场","body":"正文一","duration_s":5,"assetId":"1"},
           {"lead":"二段开场","body":"正文二","duration_s":6,"assetId":"2"}
         ]}
        """;

    private static JsonNode report(String criteria) {
        return JSON.readTree("{\"criteria\":" + criteria + "}");
    }

    @Test
    @DisplayName("正式成片的重生产计划：白名单内可修的判据都进计划，白名单外一律不进")
    void reproductionPlanFollowsWhitelist() {
        var plan = QualityRepairPlanner.planForReproduction(JSON.readTree(REPORT));

        assertTrue(plan.isPresent());
        List<String> ids = QualityRepairPlanner.criterionIds(plan.get());
        assertEquals(List.of("hook_3s", "closing_value", "pacing_variation"), ids);
        assertFalse(ids.contains("copyright_risk"), "白名单外的判据不许进修复计划");
        assertEquals(List.of("scenes[0].lead", "closing", "scenes[].duration_s"),
            QualityRepairPlanner.allowedFields(plan.get()));
    }

    @Test
    @DisplayName("抄稿只抄白名单字段：模型顺手改的标题与别的段落一律丢掉")
    void narrowKeepsOnlyWhitelistedFields() {
        var plan = QualityRepairPlanner.planForReproduction(JSON.readTree(REPORT)).orElseThrow();
        String repaired = """
            {"title":"模型偷偷改的标题","dataset_sha256":"abc","closing":"新结尾",
             "scenes":[
               {"lead":"新开场","body":"模型顺手润色的正文一","duration_s":5,"assetId":"1"},
               {"lead":"二段开场","body":"正文二","duration_s":8,"assetId":"2"}
             ]}
            """;

        QualityRepairPlanner.Narrowed narrowed = QualityRepairPlanner.narrow(ORIGINAL, repaired, plan);

        assertTrue(narrowed.violations().isEmpty(), narrowed.violations().toString());
        JsonNode merged = JSON.readTree(narrowed.script());
        assertEquals("原标题", merged.path("title").asString(""), "标题不在白名单，必须保持原样");
        assertEquals("新结尾", merged.path("closing").asString(""));
        assertEquals("新开场", merged.path("scenes").path(0).path("lead").asString(""));
        assertEquals("正文一", merged.path("scenes").path(0).path("body").asString(""),
            "正文不在白名单（这次计划没点 mid_progression），不许被顺手改");
        assertEquals(8, merged.path("scenes").path(1).path("duration_s").asInt(0));
        assertEquals(List.of("scenes[0].lead", "scenes[1].duration_s", "closing"), narrowed.changedFields());
    }

    @Test
    @DisplayName("段落数量变了就不算定向修复：不是改某一句，是重写了结构")
    void narrowRejectsStructuralChanges() {
        var plan = QualityRepairPlanner.planForReproduction(JSON.readTree(REPORT)).orElseThrow();
        String repaired = """
            {"title":"原标题","closing":"新结尾","scenes":[
               {"lead":"新开场","body":"正文一","duration_s":5},
               {"lead":"二段开场","body":"正文二","duration_s":6},
               {"lead":"多出来的一段","body":"正文三","duration_s":7}
             ]}
            """;

        QualityRepairPlanner.Narrowed narrowed = QualityRepairPlanner.narrow(ORIGINAL, repaired, plan);

        assertFalse(narrowed.violations().isEmpty());
        assertTrue(narrowed.violations().get(0).contains("段落数量"));
    }

    @Test
    @DisplayName("模型没改任何白名单字段时，不提交重生产")
    void unchangedRepairIsDetected() {
        var plan = QualityRepairPlanner.planForReproduction(JSON.readTree(REPORT)).orElseThrow();

        QualityRepairPlanner.Narrowed narrowed = QualityRepairPlanner.narrow(ORIGINAL, ORIGINAL, plan);

        assertFalse(narrowed.changed(), "一个字都没改就不该再跑一条流水线");
    }

    @Test
    @DisplayName("复检：点名判据全部 pass 才算过闸")
    void recheckRequiresEveryCriterionToPass() {
        List<String> criteria = List.of("hook_3s", "closing_value");

        assertTrue(QualityRepairService.failures(
            report("""
                [{"id":"hook_3s","verdict":"pass"},{"id":"closing_value","verdict":"pass"}]
                """), criteria).isEmpty());

        assertFalse(QualityRepairService.failures(
            report("""
                [{"id":"hook_3s","verdict":"pass"},{"id":"closing_value","verdict":"minor"}]
                """), criteria).isEmpty(), "minor 仍算未过：修复要的是过闸，不是好一点");
    }

    @Test
    @DisplayName("复检：报告里缺了那条判据按未通过处理，不默认通过")
    void recheckTreatsMissingCriterionAsFailure() {
        List<String> failures = QualityRepairService.failures(
            report("""
                [{"id":"hook_3s","verdict":"pass"}]
                """), List.of("hook_3s", "closing_value"));

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("closing_value"));
    }
}
