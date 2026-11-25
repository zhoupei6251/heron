package org.dromara.heron.service.impl;

import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L3 审稿全流程：机器判客观、模型判主观、模型不可用时客观部分照常落库。 */
@DisplayName("L3 审稿：报告产出")
class QualityReviewerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 桩模型：按 stage 返回预置内容。 */
    private static final class StubModel extends OpenAiModelClient {
        private final String reply;

        StubModel(String reply) {
            super(null, null, null);
            this.reply = reply;
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            return reply;
        }

        @Override
        public String modelName() {
            return "test-model";
        }
    }

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
             "scenes": [
               {"lead": "金价站上关键位", "body": "最新收于 {{facts.last.value}} 美元。", "duration_s": 10},
               {"lead": "较区间起点走高", "body": "涨幅 {{facts.change_pct}}%。", "duration_s": 10},
               {"lead": "创下阶段高点", "body": "区间最高 {{facts.max.value}} 美元。", "duration_s": 10}],
             "closing": "下周值得盯住这个位置。"}
            """);
    }

    private static JsonNode checks() {
        return JSON.readTree("""
            [
              {"gate": "subtitles", "passed": true, "reason": "覆盖",
               "measured": {"audioMs": 30000, "cues": 12,
                 "scene1StartMs": 0, "scene1EndMs": 8000,
                 "scene2StartMs": 8000, "scene2EndMs": 16000,
                 "scene3StartMs": 16000, "scene3EndMs": 24000,
                 "closingStartMs": 24000, "closingEndMs": 30000}},
              {"gate": "visual", "passed": true, "reason": "版式通过",
               "measured": {"blankFrames": 0, "checkErrors": 0, "checkWarnings": 0}}
            ]
            """);
    }

    private static QualityCriteria.Context ctx() {
        Map<Integer, DatasetVisualFocus.Focus> focus = new LinkedHashMap<>();
        JsonNode scenes = script().path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            focus.put(index + 1, DatasetVisualFocus.forScene(scenes.get(index), dataset()));
        }
        return new QualityCriteria.Context(1900000000000000001L, "final", script(), dataset(), checks(), null, focus);
    }

    private static ProductionRunVo run() {
        return new ProductionRunVo(1L, org.dromara.heron.domain.ProductionBizType.news_explainer, 1L,
            1900000000000000001L, 1, "token", "{}");
    }

    private static final String MODEL_REPLY = """
        {"criteria":[
          {"id":"peak_focus","verdict":"pass","class":"subjective","confidence":"medium",
           "machineVerifiable":false,"severity":"none","repairable":false,
           "evidence":[{"locator":"focus[scene2].mode","quote":"change"}],
           "reason":"第二段用变化量开场，是全片唯一承载强信号的段落。"},
          {"id":"pacing_variation","verdict":"minor","class":"subjective","confidence":"medium",
           "machineVerifiable":false,"severity":"minor","repairable":true,
           "evidence":[{"locator":"focus[scene1].mode","quote":"latest"}],
           "reason":"三段等长，节奏偏平。"}
        ]}
        """;

    @Test
    @DisplayName("十条判据一条不少，客观与主观分层")
    void reportCarriesAllTenCriteria() {
        String json = new QualityReviewer(new StubModel(MODEL_REPLY)).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        assertEquals("quality-review/1", report.path("schemaVersion").asString());
        assertEquals(10, report.path("criteria").size());
        assertEquals(7, report.path("summary").path("objectiveVerified").asInt());
        assertEquals(2, report.path("summary").path("subjectiveVerified").asInt());
    }

    @Test
    @DisplayName("模型给的客观条目一律丢弃")
    void modelObjectiveEntriesAreDropped() {
        String reply = """
            {"criteria":[
              {"id":"peak_focus","verdict":"pass","class":"subjective","confidence":"medium",
               "machineVerifiable":false,"severity":"none","repairable":false,
               "evidence":[{"locator":"focus[scene2].mode","quote":"change"}],"reason":"主观判断"},
              {"id":"pacing_variation","verdict":"pass","class":"objective","confidence":"high",
               "machineVerifiable":true,"severity":"none","repairable":false,
               "evidence":[{"locator":"focus[scene1].mode","quote":"latest"}],"reason":"模型冒充客观"}
            ]}
            """;
        String json = new QualityReviewer(new StubModel(reply)).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        JsonNode pacing = null;
        for (JsonNode item : report.path("criteria")) {
            if ("pacing_variation".equals(item.path("id").asString())) {
                pacing = item;
            }
        }
        assertFalse("objective".equals(pacing.path("class").asString()),
            "客观结论只能由代码给出，模型写了也要被丢弃");
    }

    @Test
    @DisplayName("模型给的 blocker 一律丢弃")
    void modelBlockerIsDropped() {
        String reply = """
            {"criteria":[
              {"id":"peak_focus","verdict":"fail","class":"subjective","confidence":"medium",
               "machineVerifiable":false,"severity":"blocker","repairable":false,
               "evidence":[{"locator":"focus[scene2].mode","quote":"change"}],"reason":"主观阻断"},
              {"id":"pacing_variation","verdict":"minor","class":"subjective","confidence":"medium",
               "machineVerifiable":false,"severity":"minor","repairable":true,
               "evidence":[{"locator":"focus[scene1].mode","quote":"latest"}],"reason":"节奏偏平"}
            ]}
            """;
        String json = new QualityReviewer(new StubModel(reply)).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        for (JsonNode item : report.path("criteria")) {
            assertFalse("blocker".equals(item.path("severity").asString()),
                "主观意见不得升级成阻断：" + item.path("id").asString());
        }
    }

    @Test
    @DisplayName("模型输出不是 JSON 时客观部分照常落库")
    void brokenModelOutputKeepsObjectivePart() {
        String json = new QualityReviewer(new StubModel("这不是 JSON")).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        assertEquals(10, report.path("criteria").size(), "十条判据一条都不能少");
        assertTrue(report.path("notes").toString().contains("model_output_invalid"), report.path("notes").toString());
        assertEquals(7, report.path("summary").path("objectiveVerified").asInt());
    }

    @Test
    @DisplayName("模型没给的判据落 unknown，不猜")
    void missingModelEntryBecomesUnknown() {
        String reply = """
            {"criteria":[
              {"id":"peak_focus","verdict":"pass","class":"subjective","confidence":"medium",
               "machineVerifiable":false,"severity":"none","repairable":false,
               "evidence":[{"locator":"focus[scene2].mode","quote":"change"}],"reason":"主观判断"}
            ]}
            """;
        String json = new QualityReviewer(new StubModel(reply)).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        for (JsonNode item : report.path("criteria")) {
            if ("pacing_variation".equals(item.path("id").asString())) {
                assertEquals("unknown", item.path("verdict").asString());
                assertEquals("hypothesis", item.path("class").asString());
            }
        }
    }

    @Test
    @DisplayName("报告里没有总分，只有分类计数")
    void reportHasNoTotalScore() {
        String json = new QualityReviewer(new StubModel(MODEL_REPLY)).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        List<String> fields = report.path("summary").propertyNames().stream().toList();
        assertFalse(fields.contains("totalScore"), fields.toString());
        assertFalse(fields.contains("score"), fields.toString());
        assertEquals(10, report.path("summary").path("pass").asInt() + report.path("summary").path("minor").asInt()
            + report.path("summary").path("fail").asInt() + report.path("summary").path("unknown").asInt());
    }

    @Test
    @DisplayName("配乐因响度超标被去掉时，报告里要看得见")
    void bgmDroppedIsRecordedInReport() {
        String json = new QualityReviewer(new StubModel(MODEL_REPLY)).review(run(), ctxWithBgmDropped());
        JsonNode report = JSON.readTree(json);

        assertTrue(report.path("notes").toString().contains("audio_bgm_dropped"), report.path("notes").toString());
    }

    @Test
    @DisplayName("没有去掉配乐时不写这条备注")
    void bgmDroppedIsNotRecordedWhenNormal() {
        String json = new QualityReviewer(new StubModel(MODEL_REPLY)).review(run(), ctx());
        JsonNode report = JSON.readTree(json);

        assertFalse(report.path("notes").toString().contains("audio_bgm_dropped"),
            report.path("notes").toString());
    }

    /** 带 audio_mix 闸的上下文：闸里说配乐被去掉，报告就该记一条。 */
    private static QualityCriteria.Context ctxWithBgmDropped() {
        JsonNode checks = JSON.readTree("""
            [{"gate":"audio_mix","inputLocator":"final.mp4:loudness","passed":true,
              "reason":"配乐导致响度超标，去掉配乐重混后达标","measured":{"bgmDropped":true}},
             {"gate":"subtitles","passed":true,"reason":"覆盖",
              "measured":{"scene1StartMs":0,"scene2EndMs":16000,"closingStartMs":24000,"closingEndMs":30000}}]
            """);
        Map<Integer, DatasetVisualFocus.Focus> focus = new LinkedHashMap<>();
        JsonNode scenes = script().path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            focus.put(index + 1, DatasetVisualFocus.forScene(scenes.get(index), dataset()));
        }
        return new QualityCriteria.Context(1900000000000000001L, "final", script(), dataset(), checks, null, focus);
    }
}
