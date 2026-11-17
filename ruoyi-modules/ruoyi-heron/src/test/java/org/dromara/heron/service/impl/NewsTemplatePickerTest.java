package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
     */
    @Test
    void pickAlwaysRequiresLine() {
        var picks = java.util.Arrays.stream(NewsTemplatePicker.class.getMethods())
            .filter(method -> "pick".equals(method.getName())).toList();

        assertFalse(picks.isEmpty(), "找不到 pick 方法，测试失去意义");
        assertTrue(picks.stream().allMatch(method -> method.getParameterCount() == 3),
            "pick 必须带 line 参数，否则两条线的模板会混在一起");
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
}
