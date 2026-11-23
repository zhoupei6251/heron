package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 叙事类型 → 视觉风格的对应。
 *
 * <p>这套对应是这一轮的核心：先有故事（知道这条片子讲什么），才知道该用哪套画面。
 * 映射登记在模板产物的 style.narratives 里，这里只负责按它收窄候选，
 * 以及在配置漂了的时候把它喊出来。
 */
class StoryStyleMapTest {

    private static final NewsProductionDomain.TemplateStyle BOARD =
        new NewsProductionDomain.TemplateStyle("data-board", "Dynamic Data Explainer", "动态数据解释",
            "数据看板解读、指标变化", List.of("trend_reversal", "numeric_contrast"),
            "#000000", "#ffffff", "#ff0000");
    private static final NewsProductionDomain.TemplateStyle BROADCAST =
        new NewsProductionDomain.TemplateStyle("news-broadcast", "Professional News", "专业新闻",
            "热点新闻、权威信息解读", List.of("rank_competition", "time_change"),
            "#000000", "#ffffff", "#ff0000");
    private static final NewsProductionDomain.TemplateStyle INVESTIGATE =
        new NewsProductionDomain.TemplateStyle("data-investigation", "Data Investigation", "数据调查",
            "数据异常、深度分析", List.of("counter_intuitive", "problem_evidence_reveal"),
            "#000000", "#ffffff", "#ff0000");

    private static NewsProductionDomain.TemplateInfo template(String key, NewsProductionDomain.TemplateStyle style) {
        return new NewsProductionDomain.TemplateInfo(key, key, "",
            List.of(EditorialDecisionService.DATA_BOARD), List.of("headline", "chart"), "9:16", 30, 60, style);
    }

    private static List<NewsProductionDomain.TemplateInfo> catalog() {
        return List.of(
            template("news-data-9x16", BOARD),
            template("news-data-broadcast-9x16", BROADCAST),
            template("news-data-investigate-9x16", INVESTIGATE));
    }

    @Test
    @DisplayName("按叙事收窄候选：一种叙事只留一套画面")
    void narrowKeepsOnlyTheStoryStyle() {
        var narrowed = StoryStyleMap.narrow(catalog(), "numeric_contrast");

        assertEquals(List.of("news-data-9x16"),
            narrowed.stream().map(NewsProductionDomain.TemplateInfo::key).toList());
    }

    @Test
    @DisplayName("没有叙事结论就不收窄：老数据不能因此选不出模板")
    void withoutNarrativeKeepsEveryCandidate() {
        assertEquals(3, StoryStyleMap.narrow(catalog(), null).size());
        assertEquals(3, StoryStyleMap.narrow(catalog(), "  ").size());
    }

    @Test
    @DisplayName("契约不认的叙事不参与收窄")
    void unknownNarrativeKeepsEveryCandidate() {
        assertEquals(3, StoryStyleMap.narrow(catalog(), "made_up_narrative").size());
    }

    @Test
    @DisplayName("目录登记齐了：六种叙事都有画面承接")
    void completeCatalogHasNoProblem() {
        assertTrue(StoryStyleMap.violations(catalog()).isEmpty(),
            "登记齐了就不该有告警：" + StoryStyleMap.violations(catalog()));
    }

    @Test
    @DisplayName("一个叙事落到两套风格上：收窄收不窄，等于没定")
    void narrativeOnTwoStylesIsReported() {
        var broken = List.of(
            template("news-data-9x16", BOARD),
            template("news-data-broadcast-9x16", BROADCAST),
            template("news-data-investigate-9x16", new NewsProductionDomain.TemplateStyle(
                "data-investigation", "Data Investigation", "数据调查", "深度分析",
                List.of("counter_intuitive", "time_change"),
                "#000000", "#ffffff", "#ff0000")));

        var problems = StoryStyleMap.violations(broken);

        assertTrue(problems.stream().anyMatch(item -> item.contains("time_change")),
            "重复登记的叙事必须被喊出来：" + problems);
    }

    @Test
    @DisplayName("没人承接的叙事：这类故事出不了片")
    void uncoveredNarrativeIsReported() {
        var broken = List.of(
            template("news-data-9x16", BOARD),
            template("news-data-broadcast-9x16", BROADCAST));

        var problems = StoryStyleMap.violations(broken);

        assertTrue(problems.stream().anyMatch(item -> item.contains("problem_evidence_reveal")),
            "没人承接的叙事必须被喊出来：" + problems);
    }

    @Test
    @DisplayName("登记了契约不认的叙事：登记写错了")
    void unknownNarrativeInStyleIsReported() {
        var broken = List.of(
            template("news-data-9x16", new NewsProductionDomain.TemplateStyle(
                "data-board", "Dynamic Data Explainer", "动态数据解释", "解读",
                List.of("made_up_narrative"), "#000000", "#ffffff", "#ff0000")));

        var problems = StoryStyleMap.violations(broken);

        assertTrue(problems.stream().anyMatch(item -> item.contains("made_up_narrative")),
            "登记错的叙事必须被喊出来：" + problems);
    }
}
