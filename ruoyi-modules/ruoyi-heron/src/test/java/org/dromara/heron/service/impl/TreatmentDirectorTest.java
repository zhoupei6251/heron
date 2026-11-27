package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整片创意规划契约：镜头数与实测窗对齐、词汇闭集、字数边界。
 * Treatment 缺席时 storyboard 回退确定性规则。
 */
@DisplayName("Treatment 整片创意规划契约")
class TreatmentDirectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String VALID = """
        {"narrative":{"thesis":"随机奖励让人上瘾","arc":"先让观众体验，再揭示机制","hook":"先来一次抽奖"},
         "visual":{"motif":"概率云","lensNotes":["数字先于图形","单主体"],"motionDiscipline":"数字驱动生长，末拍稳定"},
         "shots":[
          {"shotId":"s1","storyPurpose":"让观众先体验一次随机","visualIntent":"一颗不断变换的骰子占满画面",
           "treatment":"hook","narrationText":"你现在看到的结果，是随机的。","techniques":["slam"],"syncMode":"line"},
          {"shotId":"s2","storyPurpose":"建立期待","visualIntent":"连续三次结果快速闪现",
           "treatment":"statement","narrationText":"连续点了几次之后，你开始期待。","techniques":["fade-up"],"syncMode":"beat"},
          {"shotId":"s3","storyPurpose":"揭示机制","visualIntent":"概率模型展开成图表",
           "treatment":"reveal","narrationText":"这背后的机制只有一句话。","techniques":["count-up","line-grow"],"syncMode":"line"}]}
        """;

    @TempDir
    Path work;

    private TreatmentDirector.Treatment parse(String raw) {
        return new TreatmentDirector(null).parseForTest(raw);
    }

    @Test
    @DisplayName("合法规划解析为三段：叙事、视觉、逐镜清单")
    void parsesValidTreatment() {
        var treatment = parse(VALID);
        assertEquals(3, treatment.shots().size());
        assertEquals("hook", treatment.shots().get(0).get("treatment"));
        assertEquals("reveal", treatment.shots().get(2).get("treatment"));
        assertTrue(treatment.narrative().contains("随机奖励"));
        assertTrue(treatment.visual().contains("概率云"));
    }

    @Test
    @DisplayName("词汇闭集外与字数越界的规划一律拒绝")
    void rejectsInvalidTreatment() {
        assertThrows(HeronBusinessException.class,
            () -> parse(VALID.replace("\"treatment\":\"hook\"", "\"treatment\":\"explode\"")));
        assertThrows(HeronBusinessException.class,
            () -> parse(VALID.replace("\"shotId\":\"s3\"", "\"shotId\":\"x3\"")));
        assertThrows(HeronBusinessException.class, () -> parse("{}"));
        assertThrows(HeronBusinessException.class, () -> parse("这不是 JSON"));
    }

    @Test
    @DisplayName("规划镜头数必须与实测旁白段数一致；缺席时回退确定性规则")
    void storyboardAlignsWithTimelineOrFallsBack() {
        var properties = new org.dromara.heron.config.HeronProductionProperties();
        properties.setNewsProfile(Path.of("../..").toAbsolutePath().normalize().resolve("data/profiles"));
        var profile = new ProfileRegistry(properties).load(
            org.dromara.heron.domain.ProductionBizType.data_board, "data-board", ProfileRegistry.DEFAULT_VOICE);
        var script = JSON.readTree("""
            {"title":"测试","scenes":[{"lead":"一","body":"甲"},{"lead":"二","body":"乙"}]}
            """);
        var timeline = List.of(new NewsNarrationTiming.Span("scene-1", 0, 3000, false),
            new NewsNarrationTiming.Span("scene-2", 3000, 6000, true),
            new NewsNarrationTiming.Span("closing-slot", 6000, 9000, false));
        // 缺席：回退规则（首镜 hook、尾镜 close）
        var fallback = DirectorPipeline.storyboard(script, null, Map.of(), timeline, profile, null);
        assertEquals("hook", fallback.get(0).treatment());
        assertEquals("close", fallback.get(2).treatment());
        // 规划 3 镜对 3 段：Treatment 驱动
        var treatment = parse(VALID);
        var planned = DirectorPipeline.storyboard(script, null, Map.of(), timeline, profile, treatment);
        assertEquals("reveal", planned.get(2).treatment());
        assertTrue(planned.get(0).title().toString().contains("让观众先体验"));
        // 规划镜头数与实测段数不符：视同规划失败
        var shortTimeline = List.<NewsNarrationTiming.Span>of(new NewsNarrationTiming.Span("scene-1", 0, 3000, false));
        assertThrows(HeronBusinessException.class,
            () -> DirectorPipeline.storyboard(script, null, Map.of(), shortTimeline, profile, treatment));
    }
}
