package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 故事计划契约：v1 原样兼容，v2 的计划必须冻结、可回溯且被脚本逐屏消费。 */
@DisplayName("数据看板：故事计划契约 v2")
class StoryPlanContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHA = "a".repeat(64);

    private static JsonNode snapshot() {
        return JSON.readTree("""
            {"shape":"wide","source_key":"sample","source_name":"测试数据源","data_sha256":"%s","facts":{
              "first":{"date":"2026-11-05","value":4091.55},
              "last":{"date":"2026-11-10","value":4113.34},
              "change":21.79,"change_pct":0.53,
              "max":{"date":"2026-11-10","value":4113.34},
              "items":[{"value":4091.55}],
              "leader":{"name":"黄金"}}}
            """.formatted(SHA));
    }

    private static String plan() {
        return """
            {"schemaVersion":"story-plan/1","datasetSha256":"%s","hookType":"数字反差",
             "coreThesis":"区间抬升与单日波动不是一回事","scenes":[
              {"purpose":"用区间反差兑现标题","evidenceRefs":["facts.change_pct"],"beat":"HOOK",
               "emotion":"好奇","rhythm":"紧凑","expectedVisual":{"sceneType":"headline","description":"关键百分比与提问"}},
              {"purpose":"解释区间变化口径","evidenceRefs":["facts.first.value","facts.last.value"],"beat":"EVIDENCE",
               "emotion":"平静","rhythm":"平稳","expectedVisual":{"sceneType":"chart","description":"首末点连线"}},
              {"purpose":"用高点收束判断","evidenceRefs":["facts.max.value"],"beat":"CONCLUSION",
               "emotion":"释然","rhythm":"舒缓","expectedVisual":{"sceneType":"number","description":"高点数字定格"}}]}
            """.formatted(SHA);
    }

    private static String script() {
        return """
            {"schema_version":"2","dataset_sha256":"%s","source_key":"sample","chart":"line","source_name":"测试数据源",
             "story_plan":%s,"title":"金价抬升怎么看","scenes":[
              {"lead":"抬升不代表单日","body":"区间变化为 {{facts.change_pct}}%%。","duration_s":10,
               "scene_type":"headline","beat":"HOOK"},
              {"lead":"看清首末口径","body":"从 {{facts.first.value}} 到 {{facts.last.value}}。","duration_s":10,
               "scene_type":"chart","beat":"EVIDENCE"},
              {"lead":"高点落在末端","body":"区间最高为 {{facts.max.value}}。","duration_s":10,
               "scene_type":"number","beat":"CONCLUSION"}],
             "closing":"区间变化不代表单日涨跌。"}
            """.formatted(SHA, plan());
    }

    private static List<String> check(String script) {
        return StoryPlanContract.violations(JSON.readTree(script), snapshot());
    }

    @Test
    @DisplayName("同一快照的 v1 旧稿与 v2 计划稿都合法")
    void sameTopicSupportsBothVersions() {
        JsonNode v2 = JSON.readTree(script());
        ObjectNode v1 = (ObjectNode) JSON.readTree(script());
        v1.remove("schema_version");
        v1.remove("story_plan");
        assertTrue(StoryPlanContract.violations(v1, snapshot()).isEmpty());
        assertTrue(StoryPlanContract.violations(v2, snapshot()).isEmpty());
        assertEquals(List.of(), DatasetScriptContract.violations(v1, snapshot()));
        assertEquals(List.of(), DatasetScriptContract.violations(v2, snapshot()));
    }

    @Test
    @DisplayName("v2 缺计划、每屏目的或证据引用都会退回")
    void v2RequiresCompletePlan() {
        ObjectNode missing = (ObjectNode) JSON.readTree(script());
        missing.remove("story_plan");
        assertTrue(StoryPlanContract.violations(missing, snapshot()).stream()
            .anyMatch(item -> item.contains("story_plan")));

        ObjectNode plan = (ObjectNode) JSON.readTree(plan());
        ((ObjectNode) plan.path("scenes").get(1)).remove("purpose");
        ((ObjectNode) plan.path("scenes").get(1)).set("evidenceRefs", JSON.createArrayNode());
        ObjectNode script = (ObjectNode) JSON.readTree(script());
        script.set("story_plan", plan);
        List<String> items = StoryPlanContract.violations(script, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("purpose")), items.toString());
        assertTrue(items.stream().anyMatch(item -> item.contains("evidenceRefs")), items.toString());
    }

    @Test
    @DisplayName("证据必须指向快照标量，对象路径不能冒充事实")
    void evidenceMustResolveToSnapshotScalar() {
        ObjectNode plan = (ObjectNode) JSON.readTree(plan());
        ((ObjectNode) plan.path("scenes").get(0)).putArray("evidenceRefs").add("facts.leader");
        ObjectNode script = (ObjectNode) JSON.readTree(script());
        script.set("story_plan", plan);
        assertTrue(StoryPlanContract.violations(script, snapshot()).stream()
            .anyMatch(item -> item.contains("facts.leader") && item.contains("标量")));
    }

    @Test
    @DisplayName("计划哈希与快照不符时拒绝冻结")
    void rejectsSnapshotDrift() {
        ObjectNode plan = (ObjectNode) JSON.readTree(plan());
        plan.put("datasetSha256", "b".repeat(64));
        ObjectNode script = (ObjectNode) JSON.readTree(script());
        script.set("story_plan", plan);
        assertTrue(StoryPlanContract.violations(script, snapshot()).stream()
            .anyMatch(item -> item.contains("datasetSha256")));
    }

    @Test
    @DisplayName("脚本必须使用计划里的证据、节点与画面类型")
    void scriptMustConsumeFrozenPlan() {
        ObjectNode changedType = (ObjectNode) JSON.readTree(script());
        ((ObjectNode) changedType.path("scenes").get(1)).put("scene_type", "text");
        assertTrue(StoryPlanContract.violations(changedType, snapshot()).stream()
            .anyMatch(item -> item.contains("expectedVisual")));

        ObjectNode changedBeat = (ObjectNode) JSON.readTree(script());
        ((ObjectNode) changedBeat.path("scenes").get(1)).put("beat", "CONTEXT");
        assertTrue(StoryPlanContract.violations(changedBeat, snapshot()).stream()
            .anyMatch(item -> item.contains("beat")));

        ObjectNode extraEvidence = (ObjectNode) JSON.readTree(script());
        ((ObjectNode) extraEvidence.path("scenes").get(1)).put("body",
            "从 {{facts.first.value}} 到 {{facts.last.value}}，变化 {{facts.change}}。");
        assertTrue(StoryPlanContract.violations(extraEvidence, snapshot()).stream()
            .anyMatch(item -> item.contains("facts.change") && item.contains("计划外")));
    }

    @Test
    @DisplayName("证据占位符跨 lead/body 拆分时不得冒充已消费")
    void splitPlaceholderAcrossFieldsRejected() {
        ObjectNode script = (ObjectNode) JSON.readTree(script());
        ObjectNode opening = (ObjectNode) script.path("scenes").get(0);
        opening.put("lead", "区间变化为 {{facts.");
        opening.put("body", "change_pct}}%。");
        List<String> items = DatasetScriptContract.violations(script, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("未消费计划证据 facts.change_pct")),
            items.toString());
    }

    @Test
    @DisplayName("超大或溢出数组下标返回契约违例，不抛运行时异常")
    void oversizedArrayIndexesRejectedAsContractViolations() {
        for (String path : List.of("facts.items[999999999999].value", "facts.items.2147483648.value")) {
            ObjectNode plan = (ObjectNode) JSON.readTree(plan());
            ((ObjectNode) plan.path("scenes").get(0)).putArray("evidenceRefs").add(path);
            List<String> items = assertDoesNotThrow(() -> StoryPlanContract.planViolations(plan, snapshot()));
            assertTrue(items.stream().anyMatch(item -> item.contains(path) && item.contains("标量")),
                items.toString());
        }
    }

    @Test
    @DisplayName("emotion 与 rhythm 不得全篇相同")
    void uniformEmotionAndRhythmRejected() {
        ObjectNode plan = (ObjectNode) JSON.readTree(plan());
        for (JsonNode scene : plan.path("scenes")) {
            ((ObjectNode) scene).put("emotion", "好奇");
            ((ObjectNode) scene).put("rhythm", "紧凑");
        }
        List<String> items = StoryPlanContract.planViolations(plan, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("emotion") && item.contains("全篇相同")),
            items.toString());
        assertTrue(items.stream().anyMatch(item -> item.contains("rhythm") && item.contains("全篇相同")),
            items.toString());
    }

    @Test
    @DisplayName("计划自由文本必须是 JSON 字符串")
    void nonStringFreeTextRejected() {
        ObjectNode plan = (ObjectNode) JSON.readTree(plan());
        plan.put("coreThesis", 1);
        ObjectNode scene = (ObjectNode) plan.path("scenes").get(1);
        scene.put("purpose", true);
        ((ObjectNode) scene.path("expectedVisual")).put("description", 3);
        List<String> items = StoryPlanContract.planViolations(plan, snapshot());
        for (String field : List.of("coreThesis", "purpose", "description")) {
            assertTrue(items.stream().anyMatch(item -> item.contains(field) && item.contains("JSON 字符串")),
                items.toString());
        }
    }

    @Test
    @DisplayName("每屏必填字段与画面子字段缺失都进入计划违例矩阵")
    void requiredSceneFieldMatrixRejected() {
        for (String field : List.of("purpose", "evidenceRefs", "beat", "emotion", "rhythm", "expectedVisual")) {
            ObjectNode plan = (ObjectNode) JSON.readTree(plan());
            ((ObjectNode) plan.path("scenes").get(1)).remove(field);
            List<String> items = StoryPlanContract.planViolations(plan, snapshot());
            assertTrue(items.stream().anyMatch(item -> item.contains("缺字段 " + field)), items.toString());
        }
        for (String field : List.of("sceneType", "description")) {
            ObjectNode plan = (ObjectNode) JSON.readTree(plan());
            ((ObjectNode) plan.path("scenes").get(1).path("expectedVisual")).remove(field);
            List<String> items = StoryPlanContract.planViolations(plan, snapshot());
            assertTrue(items.stream().anyMatch(item -> item.contains("expectedVisual 缺字段 " + field)),
                items.toString());
        }
    }

    @Test
    @DisplayName("未知版本与未知钩子类型不放行")
    void rejectsUnknownVersionAndHook() {
        assertTrue(check(script().replace("\"schema_version\":\"2\"", "\"schema_version\":\"3\""))
            .stream().anyMatch(item -> item.contains("schema_version")));
        assertTrue(check(script().replace("\"hookType\":\"数字反差\"", "\"hookType\":\"震惊体\""))
            .stream().anyMatch(item -> item.contains("hookType")));
    }
}
