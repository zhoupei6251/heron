package org.dromara.heron.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 最终选择必须能回答两个问题：走没走原判的那条线、为什么没走。 */
@DisplayName("线内选择：原判线与回退原因")
class EditorialChoiceTest {

    private static EditorialSelection explainer() {
        return new EditorialSelection(EditorialSelection.NEWS_EXPLAINER, 7L, null, false,
            "news-9x16", "讲清楚", List.of("s7"));
    }

    @Test
    @DisplayName("主选成立：不算回退，不带原因")
    void primaryPick() {
        EditorialChoice choice = new EditorialChoice(explainer(), EditorialSelection.NEWS_EXPLAINER, false, null);
        assertEquals(EditorialSelection.NEWS_EXPLAINER, choice.finalLine());
        assertFalse(choice.fallbackApplied());
        assertNull(choice.fallbackReason());
    }

    @Test
    @DisplayName("跨线回退：原判线保留，原因必填")
    void fallbackKeepsOriginalLine() {
        EditorialChoice choice = new EditorialChoice(explainer(), EditorialSelection.DATA_BOARD, true, "data_board_empty");
        assertEquals(EditorialSelection.NEWS_EXPLAINER, choice.finalLine());
        assertEquals(EditorialSelection.DATA_BOARD, choice.originalLine());
        assertTrue(choice.fallbackApplied());
    }

    @Test
    @DisplayName("自相矛盾的选择直接拒绝，不留脏数据")
    void rejectsInconsistentShape() {
        assertThrows(IllegalStateException.class,
            () -> new EditorialChoice(explainer(), EditorialSelection.DATA_BOARD, false, "data_board_empty"));
        assertThrows(IllegalStateException.class,
            () -> new EditorialChoice(explainer(), EditorialSelection.DATA_BOARD, true, null));
        assertThrows(IllegalStateException.class,
            () -> new EditorialChoice(explainer(), "  ", false, null));
        assertThrows(IllegalStateException.class,
            () -> new EditorialChoice(null, EditorialSelection.DATA_BOARD, false, null));
    }
}
