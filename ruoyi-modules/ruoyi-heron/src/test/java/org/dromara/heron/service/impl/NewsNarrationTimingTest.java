package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.production.NewsNarrationTiming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 只用文字与字幕验证真实句界，不启动TTS或渲染器。 */
@DisplayName("新闻口播：实际字幕定位分镜")
class NewsNarrationTimingTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SCRIPT = JSON.readTree("""
        {"scenes":[{"lead":"先看结果","body":"上涨两成。","duration_s":10},
                   {"lead":"再看范围","body":"这是一段更长的解释。","duration_s":10}],
         "closing":"请记住数据口径。"}
        """);

    @Test
    @DisplayName("段落按真实句界切换，结尾不会被固定四秒截短")
    void usesSpokenBoundariesAndFullClosing() {
        JsonNode cues = JSON.readTree("""
            [{"startMs":100,"endMs":1800,"text":"先看结果。"},
             {"startMs":1800,"endMs":4500,"text":"上涨两成。"},
             {"startMs":4500,"endMs":6200,"text":"再看范围。"},
             {"startMs":6200,"endMs":17000,"text":"这是一段更长的解释。"},
             {"startMs":17000,"endMs":24500,"text":"请记住数据口径。"}]
            """);
        var timeline = NewsNarrationTiming.align(SCRIPT, cues, 25000);
        assertEquals(3, timeline.size());
        assertEquals("scene-1", timeline.get(0).id());
        assertEquals(0, timeline.get(0).startMs());
        assertEquals(4500, timeline.get(0).endMs());
        assertEquals(4500, timeline.get(1).startMs());
        assertEquals(17000, timeline.get(1).endMs());
        assertEquals("closing-slot", timeline.get(2).id());
        assertEquals(17000, timeline.get(2).startMs());
        assertEquals(25000, timeline.get(2).endMs());
        assertTrue(timeline.stream().noneMatch(NewsNarrationTiming.Span::cueInterpolated));
    }

    @Test
    @DisplayName("字幕分句不同仍按完整文本顺序对齐，不误认重复词句")
    void matchesRepeatedWordsAndDifferentCueSplits() {
        JsonNode script = JSON.readTree("""
            {"scenes":[{"lead":"同一句","body":"第一次。"},{"lead":"同一句","body":"第二次。"}],
             "closing":"结束。"}
            """);
        JsonNode cues = JSON.readTree("""
            [{"startMs":0,"endMs":1500,"text":"同一句。第一次。"},
             {"startMs":1500,"endMs":1800,"text":"同"},
             {"startMs":1800,"endMs":3000,"text":"一句。第二次。"},
             {"startMs":3000,"endMs":4000,"text":"结束。"}]
            """);
        var timeline = NewsNarrationTiming.align(script, cues, 4000);
        assertEquals(1500, timeline.get(1).startMs());
        assertEquals(3000, timeline.get(2).startMs());
    }

    @Test
    @DisplayName("只允许短cue内插值并记录估算；不能用整段字幕假装精确句界")
    void marksShortCueInterpolationAndRejectsLongOne() {
        JsonNode script = JSON.readTree("""
            {"scenes":[{"lead":"甲","body":"乙"},{"lead":"丙","body":"丁"}],"closing":"戊"}
            """);
        var timeline = NewsNarrationTiming.align(script, JSON.readTree("""
            [{"startMs":0,"endMs":500,"text":"甲乙丙丁戊"}]
            """), 600);
        assertEquals(200, timeline.get(1).startMs());
        assertEquals(400, timeline.get(2).startMs());
        assertTrue(timeline.get(1).cueInterpolated());
        assertEquals("news_audio_alignment", assertThrows(HeronBusinessException.class, () ->
            NewsNarrationTiming.align(script, JSON.readTree("""
                [{"startMs":0,"endMs":10000,"text":"甲乙丙丁戊"}]
                """), 10000)).getCode());
    }

    @Test
    @DisplayName("字幕缺字、数字被改或时间倒退时拒绝，不能退回声明时长比例")
    void rejectsTextDriftAndInvalidTimes() {
        JsonNode script = JSON.readTree("""
            {"scenes":[{"lead":"数值","body":"1.2"}],"closing":"结束"}
            """);
        assertEquals("news_audio_alignment", assertThrows(HeronBusinessException.class, () ->
            NewsNarrationTiming.align(script, JSON.readTree("""
                [{"startMs":0,"endMs":1000,"text":"数值112"},
                 {"startMs":1000,"endMs":2000,"text":"结束"}]
                """), 2000)).getCode());
        assertEquals("news_audio_alignment", assertThrows(HeronBusinessException.class, () ->
            NewsNarrationTiming.align(script, JSON.readTree("""
                [{"startMs":1000,"endMs":1500,"text":"数值1.2"},
                 {"startMs":500,"endMs":2000,"text":"结束"}]
                """), 2000)).getCode());
    }
}
