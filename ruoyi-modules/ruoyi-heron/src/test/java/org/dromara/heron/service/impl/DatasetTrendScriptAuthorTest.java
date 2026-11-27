package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.lane.databoard.DatasetScriptAuthor;
import org.dromara.heron.service.impl.lane.databoard.DatasetTitleAuthor;
import org.dromara.heron.service.impl.lane.trend.TrendAnalysis;
import org.dromara.heron.service.impl.shared.production.NewsProductionPipeline;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 趋势稿作者：判定先落库，再让模型在判定的边界内写旁白。
 *
 * <p>与看板稿的差别只有两处：提示词里必须先摆机器判定（方向以它为准），
 * 违例检查里多一道 {@link TrendContract}；这两处任一处失守，
 * 趋势线就会退化成「一份带方向词的数字复述」。
 */
@DisplayName("趋势线：脚本作者")
class DatasetTrendScriptAuthorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate START = LocalDate.parse("2026-01-01");

    private static final String TITLES = "{\"candidates\":["
        + "{\"kind\":\"数字反差\",\"title\":\"金价再创新高\"},"
        + "{\"kind\":\"疑问悬念\",\"title\":\"这波还能涨多久\"},"
        + "{\"kind\":\"利益相关\",\"title\":\"手里有黄金的看过来\"},"
        + "{\"kind\":\"时间紧迫\",\"title\":\"这周只看一个位置\"},"
        + "{\"kind\":\"反常识\",\"title\":\"涨得动吗\"}]}";

    /** 十二期单边抬升：够判 sustained_up，且阈值边界都在设计口径内。 */
    private static ObjectNode dataset() {
        ObjectNode dataset = JsonNodeFactory.instance.objectNode();
        dataset.put("contract", "dataset/1");
        dataset.put("source_key", "sample-attention");
        dataset.put("source_name", "示例序列");
        dataset.put("cadence", "daily");
        dataset.put("shape", "wide");
        dataset.put("data_sha256", "sha-sample-attention");
        ObjectNode metric = dataset.putObject("metric");
        metric.put("key", "attention");
        metric.put("label", "关注指数");
        metric.put("unit", "点");
        metric.put("precision", 2);
        var rows = dataset.putArray("rows");
        for (int index = 0; index < 12; index++) {
            var row = JsonNodeFactory.instance.arrayNode();
            row.add(START.plusDays(index).toString());
            row.add(100 + index * 3);
            rows.add(row);
        }
        // facts 由 DatasetFacts 在生产链路里算出来；这里照同一口径手写，占位符才有出处
        ObjectNode facts = dataset.putObject("facts");
        facts.putObject("first").put("date", START.toString()).put("value", 100);
        facts.putObject("last").put("date", START.plusDays(11).toString()).put("value", 133);
        facts.putObject("min").put("date", START.toString()).put("value", 100);
        facts.putObject("max").put("date", START.plusDays(11).toString()).put("value", 133);
        facts.put("change", 33);
        facts.put("change_pct", 33.0);
        facts.putObject("period").put("start_year", 2026).put("end_year", 2026);
        return dataset;
    }

    private static String trendJson() {
        return JSON.writeValueAsString(TrendAnalysis.analyze(dataset()));
    }

    private static final String PLAN = """
        {"hookType":"反常识","coreThesis":"关注指数持续抬升，但区间变化不能外推为预测","scenes":[
          {"purpose":"用区间跨度兑现问题","evidenceRefs":["facts.first.date","facts.last.date"],"beat":"HOOK",
           "emotion":"好奇","rhythm":"紧凑","expectedVisual":{"sceneType":"headline","description":"区间跨度与问题"}},
          {"purpose":"给出首末变化证据","evidenceRefs":["facts.change","facts.change_pct"],"beat":"EVIDENCE",
           "emotion":"平静","rhythm":"平稳","expectedVisual":{"sceneType":"chart","description":"首末变化折线"}},
          {"purpose":"说明判断仍需后续验证","evidenceRefs":["facts.last.value"],"beat":"CONCLUSION",
           "emotion":"克制","rhythm":"舒缓","expectedVisual":{"sceneType":"text","description":"末值与观察边界"}}]}
        """;

    private static String reply(String middleBody, String closing) {
        return "{\"title\":\"涨得动吗\",\"scenes\":["
            + "{\"lead\":\"这轮抬升有多久\",\"body\":\"从 {{facts.first.date}} 到 {{facts.last.date}}，关注指数一路上升。\","
            + "\"duration_s\":10,\"scene_type\":\"headline\",\"beat\":\"HOOK\",\"on_screen_text\":\"抬升持续多久\"},"
            + "{\"lead\":\"区间首末差多少\",\"body\":\"" + middleBody + "\",\"duration_s\":10,"
            + "\"scene_type\":\"chart\",\"beat\":\"EVIDENCE\"},"
            + "{\"lead\":\"还要观察什么\",\"body\":\"区间末值为 {{facts.last.value}} 点，是否走平仍待后续数据。\","
            + "\"duration_s\":10,\"scene_type\":\"text\",\"beat\":\"CONCLUSION\"}],"
            + "\"closing\":\"" + closing + "\",\"source_name\":\"模型自述\"}";
    }

    private static String compliant() {
        return reply("区间首末差 {{facts.change}} 点，首末变化 {{facts.change_pct}}。",
            "以上只描述 {{facts.first.date}} 到 {{facts.last.date}} 的区间变化，不构成预测。");
    }

    static class StubModel extends OpenAiModelClient {
        private final ArrayDeque<String> replies;
        private final List<String> prompts = new ArrayList<>();

        StubModel(String... replies) {
            super(null, null, null);
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            prompts.add(stage + "\n" + userPrompt);
            if ("dataset-title".equals(stage)) {
                return TITLES;
            }
            if (stage.startsWith("dataset-story-plan")) {
                return PLAN;
            }
            return replies.size() > 1 ? replies.poll() : replies.peek();
        }
    }

    private static DatasetScriptAuthor author(StubModel model) {
        return new DatasetScriptAuthor(model, new DatasetTitleAuthor(model));
    }

    private static ProductionRunVo runWithEditorial() {
        String input = "{\"schemaVersion\":\"frozen/1\",\"kind\":\"trend\","
            + "\"editorial\":{\"runKey\":\"2026-11-19T00:00\",\"line\":\"TREND_INSIGHT\","
            + "\"editorialIntent\":\"这轮抬升被当成一次性反弹，数据不支持这个说法\","
            + "\"reason\":\"连续多期同向，该讲变化意味着什么\","
            + "\"storyAngle\":\"先立区间，再讲斜率\"},"
            + "\"selection\":{\"candidateId\":1,\"dataSourceKey\":\"sample-attention\",\"digest\":false,"
            + "\"templateId\":\"news-trend-9x16\",\"storyAngle\":\"先立区间，再讲斜率\",\"sourceEvidenceIds\":[]}}";
        return new ProductionRunVo(1L, org.dromara.heron.domain.ProductionBizType.news_explainer, 1L, 1L, 1,
            "token", input);
    }

    @Test
    @DisplayName("写稿提示先摆机器判定，模型只能沿着判定写")
    void promptCarriesMachineVerdict() {
        StubModel model = new StubModel(compliant());
        DatasetScriptAuthor.Result result = author(model)
            .authorTrend(runWithEditorial(), dataset().toString(), trendJson(), false);

        assertEquals("反常识", result.hookType());
        JsonNode script = JSON.readTree(result.script());
        assertEquals("sha-sample-attention", script.path("dataset_sha256").asString());
        assertEquals("line", script.path("chart").asString());
        assertEquals("示例序列", script.path("source_name").asString());
        for (int index = 0; index < script.path("scenes").size(); index++) {
            String sceneId = String.format("scene_%03d", index + 1);
            assertEquals(sceneId, script.path("scenes").get(index).path("scene_id").asString());
            assertEquals(index, NewsProductionPipeline.sceneIndex(script, sceneId));
        }

        String writerPrompt = model.prompts.stream()
            .filter(item -> item.startsWith("trend-script\n"))
            .findFirst().orElseThrow();
        assertTrue(writerPrompt.contains("机器趋势判定"), writerPrompt);
        assertTrue(writerPrompt.contains("持续上行"), writerPrompt);
        assertTrue(writerPrompt.contains("不许改写"), writerPrompt);
        assertTrue(writerPrompt.contains("这轮抬升被当成一次性反弹"), writerPrompt);
        // 趋势稿提示里必须带上 trend/1 的完整判定，不能只给一句摘要
        assertTrue(writerPrompt.contains("\"contract\":\"trend/1\""), writerPrompt);
    }

    @Test
    @DisplayName("标题候选要落在「变化意味着什么」，不复述快照数值")
    void titleContextCarriesTrendSummary() {
        StubModel model = new StubModel(compliant());
        author(model).authorTrend(runWithEditorial(), dataset().toString(), trendJson(), false);

        String titlePrompt = model.prompts.stream()
            .filter(item -> item.startsWith("dataset-title\n"))
            .findFirst().orElseThrow();
        assertTrue(titlePrompt.contains("本期趋势判定"), titlePrompt);
        assertTrue(titlePrompt.contains("不要复述快照数值"), titlePrompt);
    }

    @Test
    @DisplayName("判定上行却写了下行词，先退回改稿，改完才收")
    void directionViolationTriggersRepair() {
        String wrong = reply("区间首末差 {{facts.change}} 点，整体一路走低。",
            "以上只描述 {{facts.first.date}} 到 {{facts.last.date}} 的区间变化。");
        StubModel model = new StubModel(wrong, compliant());

        DatasetScriptAuthor.Result result = author(model)
            .authorTrend(runWithEditorial(), dataset().toString(), trendJson(), false);

        assertTrue(model.prompts.stream().anyMatch(item -> item.startsWith("trend-script-repair\n")),
            "方向词违规必须走定向修复，直接收下等于放过红线：" + model.prompts);
        assertEquals("持续上行",
            JSON.readTree(trendJson()).path("label").asString());
        assertNotNull(result.script());
    }

    @Test
    @DisplayName("两轮都改不掉就退回写稿，不盲目重跑")
    void unrepairableDirectionViolationFails() {
        String wrong = reply("区间首末差 {{facts.change}} 点，整体一路走低。",
            "以上只描述 {{facts.first.date}} 到 {{facts.last.date}} 的区间变化。");
        StubModel model = new StubModel(wrong, wrong, wrong);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> author(model).authorTrend(runWithEditorial(), dataset().toString(), trendJson(), false));
        assertEquals("trend_script_contract", error.getCode());
        assertEquals(409, error.getStatus());
        assertTrue(error.getMessage().contains("趋势稿退回写稿"), error.getMessage());
    }

    @Test
    @DisplayName("推测词进旁白也算违例，必须退回")
    void speculationTriggersRepair() {
        String speculative = reply("区间首末差 {{facts.change}} 点，下期有望继续抬升。",
            "以上只描述 {{facts.first.date}} 到 {{facts.last.date}} 的区间变化。");
        StubModel model = new StubModel(speculative, compliant());

        author(model).authorTrend(runWithEditorial(), dataset().toString(), trendJson(), false);
        assertTrue(model.prompts.stream().anyMatch(item -> item.startsWith("trend-script-repair\n")),
            "推测词必须被 machine 挡下：" + model.prompts);
    }

    @Test
    @DisplayName("没有 editorial 节点时照常写趋势稿")
    void legacySnapshotWithoutEditorialStillWrites() {
        StubModel model = new StubModel(compliant());
        DatasetScriptAuthor.Result result = author(model)
            .authorTrend(null, dataset().toString(), trendJson(), false);
        assertEquals("涨得动吗", JSON.readTree(result.script()).path("title").asString());
    }
}
