package org.dromara.heron.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 选择对象的必填规则按线不同：数据看板要数据源，解说线要候选与证据。
 * 故意不统一成一组可空字段——那会让「选了数据看板却没有数据源」这种状态可构造。
 */
@DisplayName("线内选择：两条线各自的必填项")
class EditorialSelectionTest {

    @Test
    @DisplayName("数据看板缺数据源不能构造")
    void dataBoardRequiresDataSource() {
        assertThrows(IllegalStateException.class, () -> new EditorialSelection(
            EditorialSelection.DATA_BOARD, null, null, false, "news-data-9x16", "角度", List.of()));
    }

    @Test
    @DisplayName("解说线缺候选不能构造")
    void explainerRequiresCandidate() {
        assertThrows(IllegalStateException.class, () -> new EditorialSelection(
            EditorialSelection.NEWS_EXPLAINER, null, null, false, "news-9x16", "角度", List.of("e1")));
    }

    @Test
    @DisplayName("解说线缺证据不能构造")
    void explainerRequiresEvidence() {
        assertThrows(IllegalStateException.class, () -> new EditorialSelection(
            EditorialSelection.NEWS_EXPLAINER, 1L, null, false, "news-9x16", "角度", List.of()));
    }

    @Test
    @DisplayName("非法模板标识不能构造")
    void rejectsMalformedTemplate() {
        assertThrows(IllegalStateException.class, () -> new EditorialSelection(
            EditorialSelection.NEWS_EXPLAINER, 1L, null, false, "../../etc/passwd", "角度", List.of("e1")));
    }

    @Test
    @DisplayName("解说线的选择里不能混进数据源")
    void explainerSelectionIsConsistent() {
        EditorialSelection selection = new EditorialSelection(
            EditorialSelection.NEWS_EXPLAINER, 7L, null, false, "news-9x16", "从供应链看", List.of("e1", "e2"));
        assertFalse(selection.dataBoard());
        assertEquals(2, selection.sourceEvidenceIds().size());
    }

    @Test
    @DisplayName("数据看板的选择不需要候选")
    void dataBoardSelectionIsConsistent() {
        EditorialSelection selection = new EditorialSelection(
            EditorialSelection.DATA_BOARD, null, "worldbank-gdp", true, "news-data-9x16", "对比十年", List.of());
        assertTrue(selection.dataBoard());
        assertEquals("worldbank-gdp", selection.dataSourceKey());
    }
}
