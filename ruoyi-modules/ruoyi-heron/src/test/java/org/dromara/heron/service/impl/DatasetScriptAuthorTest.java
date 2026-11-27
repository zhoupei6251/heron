package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.lane.databoard.DatasetScriptAuthor;
import org.dromara.heron.service.impl.lane.databoard.DatasetScriptContract;
import org.dromara.heron.service.impl.lane.databoard.DatasetTitleAuthor;
import org.dromara.heron.service.impl.shared.production.NewsProductionPipeline;
import org.dromara.heron.service.impl.shared.source.SinaFuturesDatasetAdapter;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/** 作者只负责写旁白：出处、图表类型、来源文案一律由快照覆盖。 */
@DisplayName("数据看板：脚本作者")
class DatasetScriptAuthorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TITLES = "{\"candidates\":["
        + "{\"kind\":\"数字反差\",\"title\":\"金价再创新高\"},"
        + "{\"kind\":\"疑问悬念\",\"title\":\"这波还能涨多久\"},"
        + "{\"kind\":\"利益相关\",\"title\":\"手里有黄金的看过来\"},"
        + "{\"kind\":\"时间紧迫\",\"title\":\"这周只看一个位置\"},"
        + "{\"kind\":\"反常识\",\"title\":\"涨得动吗\"}]}";

    private static String dataset() {
        String body = "/*x*/ var _hf_XAU=(["
            + "{\"date\":\"2026-11-05\",\"close\":\"4091.55\"},"
            + "{\"date\":\"2026-11-06\",\"close\":\"4105.50\"},"
            + "{\"date\":\"2026-11-09\",\"close\":\"4110.04\"},"
            + "{\"date\":\"2026-11-10\",\"close\":\"4113.34\"}]);";
        return SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD, body,
            Instant.parse("2026-11-10T03:22:00Z"), 30);
    }

    private static final String PLAN = """
        {"hookType":"数字反差","coreThesis":"金价区间抬升，需要分清首末变化与单日波动","scenes":[
          {"purpose":"用末值兑现标题","evidenceRefs":["facts.last.value"],"beat":"HOOK","emotion":"好奇","rhythm":"紧凑",
           "expectedVisual":{"sceneType":"headline","description":"末值与关键位"}},
          {"purpose":"解释区间涨幅","evidenceRefs":["facts.change"],"beat":"EVIDENCE","emotion":"平静","rhythm":"平稳",
           "expectedVisual":{"sceneType":"chart","description":"区间变化折线"}},
          {"purpose":"用阶段高点收束","evidenceRefs":["facts.max.value"],"beat":"CONCLUSION","emotion":"释然","rhythm":"舒缓",
           "expectedVisual":{"sceneType":"number","description":"高点数字定格"}}]}
        """;

    private static final String STORY_PLAN = """
        {"hookType":"数字反差","coreThesis":"末值与峰值只差一点，区间抬升需要分清口径","scenes":[
          {"purpose":"用末值兑现张力","evidenceRefs":["facts.last.value"],"beat":"HOOK","emotion":"好奇","rhythm":"紧凑",
           "expectedVisual":{"sceneType":"headline","description":"末值与峰值差距"}},
          {"purpose":"证明区间末值高于起点","evidenceRefs":["facts.last.value"],"beat":"EVIDENCE","emotion":"平静","rhythm":"平稳",
           "expectedVisual":{"sceneType":"chart","description":"区间首末连线"}},
          {"purpose":"说明峰值位置","evidenceRefs":["facts.max.value"],"beat":"COMPARISON","emotion":"惊讶","rhythm":"递进",
           "expectedVisual":{"sceneType":"compare","description":"末值与峰值对比"}},
          {"purpose":"收束差距判断","evidenceRefs":["facts.max.value"],"beat":"CONCLUSION","emotion":"释然","rhythm":"舒缓",
           "expectedVisual":{"sceneType":"number","description":"峰值数字定格"}}]}
        """;

    private static EditorialStory story() {
        return new EditorialStory(
            "story/1", "numeric_contrast", "末值与峰值只差一点",
            new EditorialStory.Protagonist("metric", "黄金价格", null),
            new EditorialStory.Tension("gap", "末值与区间峰值只差一点", List.of("facts.last.value")),
            List.of(
                new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact", List.of("facts.last.value")),
                new EditorialStory.Claim("c2", "区间里的峰值出现在靠后一段", "fact", List.of("facts.max.value"))),
            new EditorialStory.Payoff("差距比看上去更小", List.of("facts.last.value")),
            new EditorialStory.Stakes("持有黄金的账户", "成本线比想象中更靠近现价"),
            List.of(),
            new EditorialStory.Basis("opportunity", 0.2, 1.5, false, 1.0, 10,
                "2026-11-01", "2026-11-10", "a".repeat(64), "opportunity", 1L));
    }

    private static String storyReply() {
        return "{\"title\":\"金价再创新高\",\"scenes\":["
            + "{\"lead\":\"金价末值与峰值只差一点\",\"body\":\"最新收于 {{facts.last.value}} 美元。\",\"duration_s\":10,"
            + "\"scene_type\":\"headline\",\"beat\":\"HOOK\"},"
            + "{\"lead\":\"区间末值高于起点\",\"body\":\"末值来到 {{facts.last.value}} 美元。\",\"duration_s\":10,"
            + "\"scene_type\":\"chart\",\"beat\":\"EVIDENCE\"},"
            + "{\"lead\":\"峰值出现在区间靠后位置\",\"body\":\"阶段高点为 {{facts.max.value}} 美元。\",\"duration_s\":10,"
            + "\"scene_type\":\"compare\",\"beat\":\"COMPARISON\"},"
            + "{\"lead\":\"差距比看上去更小\",\"body\":\"峰值定格在 {{facts.max.value}} 美元。\",\"duration_s\":10,"
            + "\"scene_type\":\"number\",\"beat\":\"CONCLUSION\"}],"
            + "\"closing\":\"区间最高 {{facts.last.value}} 美元，差距有限。\"}";
    }

    private static String reply(String secondBody) {
        return "{\"title\":\"金价再创新高\",\"scenes\":["
            + "{\"lead\":\"金价站上关键位\",\"body\":\"最新收于 {{facts.last.value}} 美元。\",\"duration_s\":10,"
            + "\"scene_type\":\"headline\",\"beat\":\"HOOK\",\"on_screen_text\":\"关键位\"},"
            + "{\"lead\":\"较区间起点走高\",\"body\":\"" + secondBody + "\",\"duration_s\":10,"
            + "\"scene_type\":\"chart\",\"beat\":\"EVIDENCE\"},"
            + "{\"lead\":\"创下阶段高点\",\"body\":\"区间最高 {{facts.max.value}} 美元。\",\"duration_s\":10,"
            + "\"scene_type\":\"number\",\"beat\":\"CONCLUSION\"}],"
            + "\"closing\":\"关注接下来的走势。\",\"source_name\":\"模型自述\"}";
    }

    static class StubModel extends OpenAiModelClient {
        private final String reply;
        private final Queue<String> plans;
        private final java.util.List<String> prompts = new java.util.ArrayList<>();

        StubModel(String reply) {
            this(reply, PLAN);
        }

        StubModel(String reply, String... plans) {
            super(null, null, null);
            this.reply = reply;
            this.plans = new ArrayDeque<>(List.of(plans));
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            prompts.add(stage + "\n" + userPrompt);
            if ("dataset-title".equals(stage)) {
                return TITLES;
            }
            if (stage.startsWith("dataset-story-plan")) {
                return plans.size() > 1 ? plans.poll() : plans.peek();
            }
            return reply;
        }
    }

    private static DatasetScriptAuthor author(StubModel model) {
        return new DatasetScriptAuthor(model, new DatasetTitleAuthor(model));
    }

    private static ProductionRunVo runWithEditorial() {
        String input = "{\"schemaVersion\":\"frozen/1\",\"kind\":\"data_board\","
            + "\"editorial\":{\"runKey\":\"2026-11-19T00:00\",\"line\":\"data_board\","
            + "\"editorialIntent\":\"金价冲高后回落的真实幅度被标题夸大\","
            + "\"reason\":\"昨日热搜在讲暴跌，数据不支持\","
            + "\"storyAngle\":\"先破再立：用区间数据纠正单日暴跌的说法\"},"
            + "\"selection\":{\"candidateId\":1,\"dataSourceKey\":\"sina-gold-xau\",\"digest\":false,"
            + "\"templateId\":\"news-data-9x16\",\"storyAngle\":\"先破再立\",\"sourceEvidenceIds\":[]}}";
        return new ProductionRunVo(1L, org.dromara.heron.domain.ProductionBizType.news_explainer, 1L, 1L, 1,
            "token", input);
    }

    @Test
    @DisplayName("冻结输入里的编辑意图与故事角度必须进入写稿提示")
    void editorialReachesWriterPrompt() {
        StubModel model = new StubModel(reply("上涨 {{facts.change}} 美元。"));
        author(model).author(runWithEditorial(), dataset(), false);
        String writerPrompt = model.prompts.stream()
            .filter(item -> item.startsWith("dataset-script\n"))
            .findFirst().orElseThrow();
        assertTrue(writerPrompt.contains("金价冲高后回落的真实幅度被标题夸大"), writerPrompt);
        assertTrue(writerPrompt.contains("昨日热搜在讲暴跌，数据不支持"), writerPrompt);
        assertTrue(writerPrompt.contains("先破再立"), writerPrompt);
    }

    @Test
    @DisplayName("先冻结故事计划再写旁白，写稿提示必须包含计划")
    void freezesPlanBeforeNarration() {
        StubModel model = new StubModel(reply("上涨 {{facts.change}} 美元。"));
        JsonNode script = JSON.readTree(author(model).author(null, dataset(), false).script());
        assertEquals("2", script.path("schema_version").asString());
        assertEquals(JSON.readTree(dataset()).path("data_sha256").asString(),
            script.path("story_plan").path("datasetSha256").asString());
        assertEquals("story-plan/1", script.path("story_plan").path("schemaVersion").asString());
        for (int index = 0; index < script.path("scenes").size(); index++) {
            assertEquals(String.format("scene_%03d", index + 1), script.path("scenes").get(index).path("scene_id").asString());
        }
        for (int index = 0; index < script.path("scenes").size(); index++) {
            assertEquals(index, NewsProductionPipeline.sceneIndex(script, String.format("scene_%03d", index + 1)));
        }
        assertTrue(NewsProductionPipeline.replaceSceneBySceneId("scene_002", script,
            script.path("scenes").get(1)).path("scenes").get(1).path("scene_id").asString().equals("scene_002"));
        int planCall = model.prompts.indexOf(model.prompts.stream()
            .filter(item -> item.startsWith("dataset-story-plan\n")).findFirst().orElseThrow());
        int scriptCall = model.prompts.indexOf(model.prompts.stream()
            .filter(item -> item.startsWith("dataset-script\n")).findFirst().orElseThrow());
        assertTrue(planCall < scriptCall, model.prompts.toString());
        assertTrue(model.prompts.get(scriptCall).contains("facts.last.value"), model.prompts.get(scriptCall));
    }

    @Test
    @DisplayName("计划证据不可解析时拒绝进入旁白阶段")
    void invalidPlanStopsBeforeNarration() {
        String invalid = PLAN.replace("facts.last.value", "facts.missing");
        StubModel model = new StubModel(reply("上涨 {{facts.change}} 美元。"), invalid);
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> author(model).author(null, dataset(), false));
        assertEquals("dataset_story_plan_contract", error.getCode());
        assertTrue(model.prompts.stream().noneMatch(item -> item.startsWith("dataset-script\n")));
    }

    @Test
    @DisplayName("story/1 位置与容量错误在计划闸拒绝，旁白阶段不得开始")
    void invalidStoryPlanPositionsStopBeforeNarration() {
        ObjectNode wrongOpening = (ObjectNode) JSON.readTree(STORY_PLAN);
        ((ObjectNode) wrongOpening.path("scenes").get(0)).putArray("evidenceRefs").add("facts.max.value");
        assertPlanRejected(wrongOpening.toString(), "开场引用了张力之外的路径");

        ObjectNode tooFewMiddle = (ObjectNode) JSON.readTree(STORY_PLAN);
        ((tools.jackson.databind.node.ArrayNode) tooFewMiddle.path("scenes")).remove(2);
        assertPlanRejected(tooFewMiddle.toString(), "中段只有 1 段");

        ObjectNode missingClaim = (ObjectNode) JSON.readTree(STORY_PLAN);
        ((ObjectNode) missingClaim.path("scenes").get(2)).putArray("evidenceRefs").add("facts.last.value");
        assertPlanRejected(missingClaim.toString(), "事实主张 c2 的出处没有出现在中段");
    }

    @Test
    @DisplayName("计划修复收到位置违例并成功后，旁白阶段才获准开始")
    void storyPlanRepairsBeforeNarration() {
        ObjectNode wrongOpening = (ObjectNode) JSON.readTree(STORY_PLAN);
        ((ObjectNode) wrongOpening.path("scenes").get(0)).putArray("evidenceRefs").add("facts.max.value");
        StubModel model = new StubModel(storyReply(), wrongOpening.toString(), STORY_PLAN);
        JsonNode script = JSON.readTree(author(model).author(null, dataset(), false, story()).script());
        assertEquals(JSON.readTree(STORY_PLAN).path("scenes"), script.path("story_plan").path("scenes"));
        String repairPrompt = model.prompts.stream()
            .filter(item -> item.startsWith("dataset-story-plan-repair\n"))
            .findFirst().orElseThrow();
        assertTrue(repairPrompt.contains("开场引用了张力之外的路径"), repairPrompt);
        assertTrue(repairPrompt.contains("主张列表"), repairPrompt);
        assertTrue(model.prompts.stream().anyMatch(item -> item.startsWith("dataset-script\n")));
    }

    private static void assertPlanRejected(String plan, String message) {
        StubModel model = new StubModel(storyReply(), plan);
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> author(model).author(null, dataset(), false, story()));
        assertEquals("dataset_story_plan_contract", error.getCode());
        assertTrue(error.getMessage().contains(message), error.getMessage());
        assertTrue(model.prompts.stream().noneMatch(item -> item.startsWith("dataset-script\n")),
            model.prompts.toString());
    }

    @Test
    @DisplayName("修复时模型不能改写已冻结故事计划")
    void repairCannotRewriteFrozenPlan() {
        StubModel model = new StubModel(reply("上涨 {{facts.change}} 美元。"));
        JsonNode original = JSON.readTree(author(model).author(null, dataset(), false).script());
        String tampered = reply("上涨 {{facts.change}} 美元。")
            .replace("\"title\"", "\"story_plan\":{\"hookType\":\"反常识\"},\"title\"");
        JsonNode repaired = JSON.readTree(author(new StubModel(tampered))
            .repair(null, dataset(), original.toString(), List.of("结尾需要补充判断")).script());
        assertEquals(original.path("story_plan"), repaired.path("story_plan"));
    }

    @Test
    @DisplayName("v1 旧稿修复后仍按 v1 放行，不强制补故事计划")
    void legacyRepairStaysV1() {
        JsonNode generated = JSON.readTree(author(new StubModel(reply("上涨 {{facts.change}} 美元。")))
            .author(null, dataset(), false).script());
        ((tools.jackson.databind.node.ObjectNode) generated).remove("schema_version");
        ((tools.jackson.databind.node.ObjectNode) generated).remove("story_plan");
        JsonNode repaired = JSON.readTree(author(new StubModel(reply("上涨 {{facts.change}} 美元。")))
            .repair(null, dataset(), generated.toString(), List.of("结尾需要补充判断")).script());
        assertFalse(repaired.has("schema_version"));
        assertFalse(repaired.has("story_plan"));
        assertEquals(List.of(), DatasetScriptContract.violations(repaired, JSON.readTree(dataset())));
    }

    @Test
    @DisplayName("审稿后定向修复仍带同一份编辑意图，不因改稿退回通用稿")
    void repairKeepsEditorial() {
        StubModel model = new StubModel(reply("上涨 {{facts.change}} 美元。"));
        String script = author(model).author(runWithEditorial(), dataset(), false).script();
        author(model).repair(runWithEditorial(), dataset(), script, java.util.List.of("结尾把区间说成单日"));
        String repairPrompt = model.prompts.stream()
            .filter(item -> item.startsWith("dataset-script-repair\n"))
            .findFirst().orElseThrow();
        assertTrue(repairPrompt.contains("金价冲高后回落的真实幅度被标题夸大"), repairPrompt);
        assertTrue(repairPrompt.contains("先破再立"), repairPrompt);
    }

    @Test
    @DisplayName("老快照没有 editorial 节点时照常写稿，不抛错")
    void legacySnapshotWithoutEditorialStillWrites() {
        String script = author(new StubModel(reply("上涨 {{facts.change}} 美元。"))).author(null, dataset(), false).script();
        assertEquals("金价再创新高", JSON.readTree(script).path("title").asString());
    }

    @Test
    @DisplayName("模型只交旁白，哈希/源/图表/来源由快照覆盖")
    void overridesEvidence() {
        String script = author(new StubModel(reply("上涨 {{facts.change}} 美元。")))
            .author(null, dataset(), false).script();
        JsonNode node = JSON.readTree(script);
        assertEquals(JSON.readTree(dataset()).path("data_sha256").asString(), node.path("dataset_sha256").asString());
        assertEquals("sina-gold-xau", node.path("source_key").asString());
        assertEquals("line", node.path("chart").asString());
        assertEquals("来源：新浪财经", node.path("source_name").asString());
    }

    @Test
    @DisplayName("选中标题的钩子类型必须带出来，丢了就再也归因不回来")
    void keepsHookTypeOfSelectedTitle() {
        DatasetScriptAuthor.Result result = author(new StubModel(reply("上涨 {{facts.change}} 美元。")))
            .author(null, dataset(), false);
        assertEquals("数字反差", result.hookType());
    }

    @Test
    @DisplayName("模型把 JSON 包进 markdown 围栏时先剥围栏再解析")
    void acceptsFencedJson() {
        String fenced = "```json\n" + reply("上涨 {{facts.change}} 美元。") + "\n```";
        String script = author(new StubModel(fenced)).author(null, dataset(), false).script();
        assertEquals("金价再创新高", JSON.readTree(script).path("title").asString());
    }

    @Test
    @DisplayName("两次都写裸数字就退回，不盲目重跑")
    void rejectsBareDigitsAfterRepair() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> author(new StubModel(reply("上涨 3%。"))).author(null, dataset(), false));
        assertTrue(error.getMessage().contains("裸数字"), error.getMessage());
    }

    @Test
    @DisplayName("流水线变量带上 chart 与整份快照，文本仍是文章线那套")
    void pipelineVariablesCarryChartAndDataset() {
        String dataset = dataset();
        String script = author(new StubModel(reply("上涨 {{facts.change}} 美元。")))
            .author(null, dataset, false).script();
        JsonNode vars = JSON.readTree(NewsProductionPipeline.dataTemplateVariables(JSON.readTree(script),
            JSON.readTree(dataset)));
        assertEquals("line", vars.path("chart").asString());
        assertEquals(JSON.readTree(dataset).path("data_sha256").asString(),
            JSON.readTree(vars.path("dataset_json").asString()).path("data_sha256").asString());
        assertEquals("金价再创新高", vars.path("title").asString());
        assertEquals(3, vars.path("scene_count").asInt());
        assertEquals("最新收于 4113.34 美元。", vars.path("scene1_body").asString());
        assertEquals("latest", JSON.readTree(vars.path("scene1_focus").asString()).path("mode").asString());
        assertEquals("change", JSON.readTree(vars.path("scene2_focus").asString()).path("mode").asString());
    }
}
