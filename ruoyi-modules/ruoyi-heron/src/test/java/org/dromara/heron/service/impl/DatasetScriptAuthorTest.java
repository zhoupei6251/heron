package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

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

    private static String reply(String secondBody) {
        return "{\"title\":\"金价再创新高\",\"scenes\":["
            + "{\"lead\":\"金价站上关键位\",\"body\":\"最新收于 {{facts.last.value}} 美元。\",\"duration_s\":15},"
            + "{\"lead\":\"较前一日走高\",\"body\":\"" + secondBody + "\",\"duration_s\":15},"
            + "{\"lead\":\"创下阶段高点\",\"body\":\"区间最高 {{facts.max.value}} 美元。\",\"duration_s\":15}],"
            + "\"closing\":\"关注接下来的走势。\",\"source_name\":\"模型自述\"}";
    }

    static class StubModel extends OpenAiModelClient {
        private final String reply;

        StubModel(String reply) {
            super(null, null, null);
            this.reply = reply;
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            return "dataset-title".equals(stage) ? TITLES : reply;
        }
    }

    private static DatasetScriptAuthor author(StubModel model) {
        return new DatasetScriptAuthor(model, new DatasetTitleAuthor(model));
    }

    @Test
    @DisplayName("模型只交旁白，哈希/源/图表/来源由快照覆盖")
    void overridesEvidence() {
        String script = author(new StubModel(reply("上涨 {{facts.change}} 美元。")))
            .author(null, dataset(), false);
        JsonNode node = JSON.readTree(script);
        assertEquals(JSON.readTree(dataset()).path("data_sha256").asString(), node.path("dataset_sha256").asString());
        assertEquals("sina-gold-xau", node.path("source_key").asString());
        assertEquals("line", node.path("chart").asString());
        assertEquals("来源：新浪财经", node.path("source_name").asString());
    }

    @Test
    @DisplayName("模型把 JSON 包进 markdown 围栏时先剥围栏再解析")
    void acceptsFencedJson() {
        String fenced = "```json\n" + reply("上涨 {{facts.change}} 美元。") + "\n```";
        String script = author(new StubModel(fenced)).author(null, dataset(), false);
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
            .author(null, dataset, false);
        JsonNode vars = JSON.readTree(NewsProductionPipeline.dataTemplateVariables(JSON.readTree(script),
            JSON.readTree(dataset)));
        assertEquals("line", vars.path("chart").asString());
        assertEquals(JSON.readTree(dataset).path("data_sha256").asString(),
            JSON.readTree(vars.path("dataset_json").asString()).path("data_sha256").asString());
        assertEquals("金价再创新高", vars.path("title").asString());
        assertEquals(3, vars.path("scene_count").asInt());
    }
}
