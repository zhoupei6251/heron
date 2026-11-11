package org.dromara.heron.service.impl;

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
}
