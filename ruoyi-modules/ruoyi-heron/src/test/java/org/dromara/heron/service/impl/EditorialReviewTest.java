package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 审稿只认 BLOCKER/MAJOR 必须改，MINOR 只记录。 */
@DisplayName("独立审稿：分级与拒绝")
class EditorialReviewTest {

    @Test
    @DisplayName("涨跌说反要拦下")
    void blocksContradiction() {
        var verdict = EditorialReviewer.plan("""
            {"result":"FAIL","issues":[
              {"severity":"BLOCKER","segmentId":"scene2","description":"事实是下跌，文案写成上涨","suggestion":"改为下降"}
            ],"minorSuggestions":[]}
            """);
        assertTrue(verdict.blocked());
        assertEquals(1, verdict.blockingNotes().size());
        assertTrue(verdict.blockingNotes().get(0).contains("scene2"));
    }

    @Test
    @DisplayName("只有 MINOR 不拦，也不触发修复")
    void minorDoesNotBlock() {
        var verdict = EditorialReviewer.plan("""
            {"result":"PASS","issues":[
              {"severity":"MINOR","segmentId":"closing","description":"结尾略拖","suggestion":"压短"}
            ],"minorSuggestions":["结尾可以更利落"]}
            """);
        assertFalse(verdict.blocked(), "MINOR 只记录，不让文案无限重写");
        assertEquals(1, verdict.minorSuggestions().size());
    }

    @Test
    @DisplayName("PASS 且无问题是常态")
    void passClean() {
        var verdict = EditorialReviewer.plan("{\"result\":\"PASS\",\"issues\":[],\"minorSuggestions\":[]}");
        assertFalse(verdict.blocked());
        assertTrue(verdict.blockingNotes().isEmpty());
    }

    @Test
    @DisplayName("自相矛盾的输出一律拒绝")
    void rejectsInconsistent() {
        assertEquals("editorial_review_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialReviewer.plan("{\"result\":\"FAIL\",\"issues\":[]}")).getCode());
        assertEquals("editorial_review_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialReviewer.plan("""
                {"result":"PASS","issues":[{"severity":"BLOCKER","description":"x"}]}
                """)).getCode());
        assertEquals("editorial_review_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialReviewer.plan("""
                {"result":"FAIL","issues":[{"severity":"FATAL","description":"x"}]}
                """)).getCode());
        assertEquals("editorial_review_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialReviewer.plan("不是 JSON")).getCode());
    }
}
