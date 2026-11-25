package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.dromara.heron.domain.HeronNewsWork;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 选模板的硬约束：模型只能原样选、选错就退回最久未用。 */
class NewsTemplatePickerTest {
    private static final NewsProductionDomain.TemplateInfo POSTER = new NewsProductionDomain.TemplateInfo("poster", "大字报", "");
    private static final NewsProductionDomain.TemplateInfo MAGAZINE = new NewsProductionDomain.TemplateInfo("magazine", "杂志", "");
    private static final NewsProductionDomain.TemplateInfo MONO = new NewsProductionDomain.TemplateInfo("mono", "数据", "");
    private static final List<NewsProductionDomain.TemplateInfo> ALL = List.of(POSTER, MAGAZINE, MONO);

    @Test
    void modelReplyMustBeAnExactCandidate() {
        assertEquals("mono", NewsTemplatePicker.choose(List.of("poster", "mono"), "mono"));
        assertEquals("mono", NewsTemplatePicker.choose(List.of("poster", "mono"), "  mono\n"));
        assertNull(NewsTemplatePicker.choose(List.of("poster", "mono"), "我选择 mono"));
        assertNull(NewsTemplatePicker.choose(List.of("poster", "mono"), "rail"));
        assertNull(NewsTemplatePicker.choose(List.of("poster"), null));
    }

    @Test
    void neverUsedWinsThenOldest() {
        // recent 是最新在前：mono 最近用过，magazine 次之，poster 从未用过
        assertEquals("poster", NewsTemplatePicker.leastRecentlyUsed(ALL, List.of("mono", "magazine")));

        // 三套都用过：取 recent 末尾（最久）且在候选里的那套
        assertEquals("magazine", NewsTemplatePicker.leastRecentlyUsed(ALL, List.of("mono", "poster", "magazine")));

        // 候选被裁剪后只认剩下的：poster 从未用过，仍优先
        assertEquals("poster", NewsTemplatePicker.leastRecentlyUsed(List.of(POSTER, MONO), List.of("mono", "magazine")));

        // 候选全都用过时，取 recent 末尾里仍属候选的那套
        assertEquals("mono", NewsTemplatePicker.leastRecentlyUsed(List.of(POSTER, MONO), List.of("poster", "magazine", "mono")));
    }

    /**
     * 不提供「不传线路」的 pick 重载。
     *
     * <p>曾经有过：人工认领走两参版本，两条线的模板一起进候选，于是有正文的文章候选
     * 选中了 news-data-9x16，脚本却按解说线写 text 场景，契约校验直接失败
     * （error_code = scene_type_unsupported）。线路必须由调用方交代清楚。
     *
     * <p>带叙事的那一版是四参：叙事决定视觉风格，风格再决定模板。
     */
    @Test
    void pickAlwaysRequiresLine() {
        var picks = java.util.Arrays.stream(NewsTemplatePicker.class.getMethods())
            .filter(method -> "pick".equals(method.getName())).toList();

        assertFalse(picks.isEmpty(), "找不到 pick 方法，测试失去意义");
        assertTrue(picks.stream().allMatch(method -> method.getParameterCount() >= 3),
            "pick 必须带 line 参数，否则两条线的模板会混在一起");
        assertTrue(picks.stream().noneMatch(method -> method.getParameterCount() == 2),
            "pick 不能没有 line 参数");
    }

    @Test
    @DisplayName("模板按声明过滤线路：数据看板模板不进解说线候选")
    void templatesFilterByDeclaredLine() {
        var dataBoard = new NewsProductionDomain.TemplateInfo("news-data-9x16", "数据看板", "",
            List.of(EditorialDecisionService.DATA_BOARD), List.of("headline", "chart"), "9:16", 30, 60, null);
        var explainer = new NewsProductionDomain.TemplateInfo("news-9x16", "快报", "",
            List.of(EditorialDecisionService.NEWS_EXPLAINER), List.of("headline", "text"), "9:16", 40, 90, null);

        assertFalse(dataBoard.supports(EditorialDecisionService.NEWS_EXPLAINER));
        assertTrue(explainer.supports(EditorialDecisionService.NEWS_EXPLAINER));
        assertTrue(dataBoard.supports(EditorialDecisionService.DATA_BOARD));
        assertFalse(explainer.supports(EditorialDecisionService.DATA_BOARD));
    }

    @Test
    @DisplayName("最近用过的模板只认在册的：已删模板和没选版式的作品都不占名额")
    void recentOnlyCountsInstalledTemplates() {
        // 最新一条用的是已删模板，第二条还没选版式——都不该挤掉后面真正在册的记录
        var works = List.of(work("news-poster-9x16"), work(null), work(MAGAZINE.key()), work(POSTER.key()),
            work("knowledge-mono-9x16"), work(MONO.key()));

        assertEquals(List.of("magazine", "poster", "mono"), NewsTemplatePicker.recentInstalled(works, ALL_KEYS));
        // 没有在册记录时是空表，交给「从未用过优先」，不抛异常
        assertEquals(List.of(), NewsTemplatePicker.recentInstalled(works, List.of("news-9x16")));
    }

    @Test
    @DisplayName("最近用过最多记 5 套，且同一套重复出现只算一次")
    void recentIsDeduplicatedAndCapped() {
        var works = List.of(work(MONO.key()), work(MONO.key()), work(MAGAZINE.key()), work(POSTER.key()));

        assertEquals(List.of("mono", "magazine", "poster"), NewsTemplatePicker.recentInstalled(works, ALL_KEYS));
    }

    private static HeronNewsWork work(String templateKey) {
        var work = new HeronNewsWork();
        work.setTemplateKey(templateKey);
        return work;
    }

    private static final List<String> ALL_KEYS = List.of("poster", "magazine", "mono");

    private static final NewsProductionDomain.TemplateStyle DATA_BOARD_STYLE =
        new NewsProductionDomain.TemplateStyle("data-board", "Dynamic Data Explainer", "动态数据解释",
            "数据看板解读", List.of("trend_reversal", "numeric_contrast"), "#000000", "#ffffff", "#18b7c9");

    private static NewsProductionDomain.TemplateInfo dataBoard(String key, VisualMode mode) {
        return new NewsProductionDomain.TemplateInfo(key, key, "",
            List.of(EditorialDecisionService.DATA_BOARD), List.of("headline", "chart"), "9:16", 30, 60,
            DATA_BOARD_STYLE, mode);
    }

    @Test
    @DisplayName("视觉模式决定实现：2D 不会挑到 3D 模板，3D 也不会落到 2D")
    void visualModeSelectsImplementation() {
        var picker = new NewsTemplatePicker(null, null, null);
        var pool = List.of(dataBoard("board-2d", VisualMode.TWO_D), dataBoard("board-3d", VisualMode.THREE_D));

        assertEquals("board-2d", picker.pickFrom(pool, "标题", "摘要",
            EditorialDecisionService.DATA_BOARD, "numeric_contrast", VisualMode.TWO_D));
        assertEquals("board-3d", picker.pickFrom(pool, "标题", "摘要",
            EditorialDecisionService.DATA_BOARD, "numeric_contrast", VisualMode.THREE_D));
    }

    @Test
    @DisplayName("要求 3D 却没有 3D 模板：拒绝这条候选，不悄悄退回 2D")
    void threeDWithoutTemplateIsRejected() {
        var picker = new NewsTemplatePicker(null, null, null);
        var only2d = List.of(dataBoard("board-2d", VisualMode.TWO_D));

        assertNull(picker.pickFrom(only2d, "标题", "摘要",
            EditorialDecisionService.DATA_BOARD, "numeric_contrast", VisualMode.THREE_D));
    }
}
