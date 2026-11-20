package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** L3 审稿的客观层：九条判据里能被机器数出来的那部分。 */
@DisplayName("L3 审稿：客观判据")
class QualityCriteriaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode dataset() {
        return JSON.readTree("""
            {
              "data_sha256": "abc123",
              "source_key": "test-source",
              "shape": "wide",
              "facts": {
                "first": {"date": "2026-11-01", "value": 100.0},
                "last": {"date": "2026-11-10", "value": 120.0},
                "min": {"date": "2026-11-01", "value": 100.0},
                "max": {"date": "2026-11-10", "value": 120.0},
                "change": 20.0,
                "change_pct": 20.0,
                "period": {"start_year": 2026, "end_year": 2026}
              }
            }
            """);
    }

    private static JsonNode script() {
        return JSON.readTree("""
            {
              "title": "金价创新高",
              "chart": "line",
              "scenes": [
                {"lead": "金价站上关键位", "body": "最新收于 {{facts.last.value}} 美元。", "duration_s": 10},
                {"lead": "较区间起点走高", "body": "涨幅 {{facts.change_pct}}%。", "duration_s": 10},
                {"lead": "创下阶段高点", "body": "区间最高 {{facts.max.value}} 美元。", "duration_s": 10}
              ],
              "closing": "下周会怎样，值得盯住这个位置。"
            }
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
               "measured": {"blankFrames": 0, "checkErrors": 0, "checkWarnings": 0}},
              {"gate": "video_signal", "passed": true, "reason": "画面在动",
               "measured": {"blackSpans": 0, "longestBlackSeconds": 0,
                 "freezeSpans": 0, "longestFreezeSeconds": 1.2, "videoSeconds": 38.1}}
            ]
            """);
    }

    private static QualityCriteria.Context context(JsonNode script, JsonNode dataset, JsonNode checks) {
        Map<Integer, DatasetVisualFocus.Focus> focus = new LinkedHashMap<>();
        JsonNode scenes = script.path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            focus.put(index + 1, DatasetVisualFocus.forScene(scenes.get(index), dataset));
        }
        return new QualityCriteria.Context(1900000000000000001L, "final", script, dataset, checks, null, focus);
    }

    private static QualityCriteria.Criterion criterion(QualityCriteria.Context ctx, String id) {
        return QualityCriteria.judge(ctx).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("合规稿：开场兑现、事实引用够、结尾不空泛")
    void compliantScriptPassesMachineCriteria() {
        var ctx = context(script(), dataset(), checks());
        assertEquals("pass", criterion(ctx, "hook_3s").verdict());
        assertEquals("pass", criterion(ctx, "early_payoff").verdict());
        assertEquals("pass", criterion(ctx, "mid_progression").verdict());
        assertEquals("pass", criterion(ctx, "visual_sync").verdict());
        assertEquals("pass", criterion(ctx, "style_consistency").verdict());
        assertEquals("pass", criterion(ctx, "no_redundancy").verdict());
        assertEquals("pass", criterion(ctx, "closing_value").verdict());
    }

    @Test
    @DisplayName("开场先讲来源口径即判不通过")
    void metaOpeningFails() {
        ObjectNode script = (ObjectNode) script();
        ((ObjectNode) script.path("scenes").get(0)).put("lead", "数据来自新浪财经");
        var ctx = context(script, dataset(), checks());
        var hook = criterion(ctx, "hook_3s");
        assertEquals("fail", hook.verdict());
        assertTrue(hook.reason().contains("命中开场元话语"), hook.reason());
    }

    @Test
    @DisplayName("开场复读标题即判不通过")
    void titleRepeatFails() {
        ObjectNode script = (ObjectNode) script();
        ((ObjectNode) script.path("scenes").get(0)).put("lead", "金价创新高，今天继续涨");
        var ctx = context(script, dataset(), checks());
        assertEquals("fail", criterion(ctx, "hook_3s").verdict());
    }

    @Test
    @DisplayName("相邻两段引用完全相同的事实路径即判平铺")
    void identicalRefsFailMidProgression() {
        ObjectNode script = (ObjectNode) script();
        ((ObjectNode) script.path("scenes").get(1)).put("body", "同样收于 {{facts.last.value}} 美元。");
        var ctx = context(script, dataset(), checks());
        var mid = criterion(ctx, "mid_progression");
        assertEquals("minor", mid.verdict());
        assertTrue(mid.reason().contains("完全相同事实路径"), mid.reason());
    }

    @Test
    @DisplayName("连续两对相邻段重复事实路径即判不通过")
    void repeatedAdjacentRefsFailMidProgression() {
        ObjectNode script = (ObjectNode) script();
        ((ObjectNode) script.path("scenes").get(1)).put("body", "同样收于 {{facts.last.value}} 美元。");
        ((ObjectNode) script.path("scenes").get(2)).put("body", "还是收于 {{facts.last.value}} 美元。");
        var ctx = context(script, dataset(), checks());
        assertEquals("fail", criterion(ctx, "mid_progression").verdict());
    }

    @Test
    @DisplayName("结尾是空关注引导即判不通过")
    void emptyClosingFails() {
        ObjectNode script = (ObjectNode) script();
        script.put("closing", "持续关注我们");
        var ctx = context(script, dataset(), checks());
        assertEquals("fail", criterion(ctx, "closing_value").verdict());
    }

    @Test
    @DisplayName("版式有错即判风格不统一")
    void checkErrorsFailStyleConsistency() {
        ObjectNode checks = (ObjectNode) checks().get(1);
        ((ObjectNode) checks.path("measured")).put("checkErrors", 2);
        var ctx = context(script(), dataset(), JSON.readTree("[" + checks + "]"));
        assertEquals("fail", criterion(ctx, "style_consistency").verdict());
    }

    @Test
    @DisplayName("模型负责的两条不由机器定论")
    void modelDecidedCriteriaAreNotMachineJudged() {
        var ctx = context(script(), dataset(), checks());
        List<String> judged = QualityCriteria.judge(ctx).stream().map(QualityCriteria.Criterion::id).toList();
        assertFalse(judged.contains("peak_focus"), judged.toString());
        assertFalse(judged.contains("pacing_variation"), judged.toString());
        assertEquals(8, judged.size());
    }

    @Test
    @DisplayName("画面长时间不动判 fail：静止的图配旁白就是 PPT")
    void stillPictureFails() {
        var ctx = context(script(), dataset(), JSON.readTree("""
            [{"gate": "video_signal", "passed": true, "reason": "有静止段",
              "measured": {"longestBlackSeconds": 0, "longestFreezeSeconds": 5.4}}]
            """));
        assertEquals("fail", criterion(ctx, "picture_motion").verdict());
    }

    @Test
    @DisplayName("没有画面信号检测结果时交 unknown，不猜")
    void pictureMotionUnknownWithoutSignal() {
        var ctx = context(script(), dataset(), JSON.readTree("[]"));
        assertEquals("unknown", criterion(ctx, "picture_motion").verdict());
    }

    @Test
    @DisplayName("节奏事实由实测时长算出，不看声明时长")
    void pacingFactsComeFromMeasuredDurations() {
        var ctx = context(script(), dataset(), checks());
        var facts = QualityCriteria.pacingFacts(ctx);
        assertEquals(8000L, facts.longestMs());
        assertEquals(8000L, facts.shortestMs());
        assertEquals(1.0d, facts.ratio(), 0.001d);
        assertEquals(0, facts.declaredMismatchCount());
    }

    @Test
    @DisplayName("没有事实集时画面判据给不出结论")
    void withoutDatasetVisualSyncIsUnknown() {
        ObjectNode script = (ObjectNode) script();
        var ctx = new QualityCriteria.Context(1L, "final", script, null, checks(), null, Map.of());
        assertEquals("unknown", criterion(ctx, "visual_sync").verdict());
    }
}
