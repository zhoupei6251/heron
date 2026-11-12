package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审稿契约收紧：FAIL 必须带 BLOCKER/MAJOR。
 * 之前只要求 issue 非空，于是「FAIL + 三条 MINOR」能过——模型说了不通过，blocked() 却放行。
 */
@DisplayName("审稿契约：FAIL 必须有阻断项")
class EditorialReviewContractTest {

    @Test
    @DisplayName("FAIL 只带 MINOR 被拒收")
    void failWithOnlyMinorIsRejected() {
        HeronBusinessException e = assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan("""
            {"result":"FAIL","issues":[
              {"severity":"MINOR","segmentId":"scene1","description":"措辞可以更好","suggestion":""}
            ],"minorSuggestions":[]}
            """));
        assertEquals("editorial_review_invalid", e.getCode());
    }

    @Test
    @DisplayName("FAIL 带 MAJOR 合法")
    void failWithMajorIsAccepted() {
        var verdict = EditorialReviewer.plan("""
            {"result":"FAIL","issues":[
              {"severity":"MAJOR","segmentId":"scene2","description":"程度被夸大","suggestion":"改为小幅"}
            ],"minorSuggestions":[]}
            """);
        assertTrue(verdict.blocked());
    }

    @Test
    @DisplayName("FAIL 没有任何 issues 被拒收")
    void failWithoutIssuesIsRejected() {
        assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan("""
            {"result":"FAIL","issues":[],"minorSuggestions":[]}
            """));
    }

    @Test
    @DisplayName("未知严重程度被拒收，不默认成 MINOR")
    void unknownSeverityIsRejected() {
        assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan("""
            {"result":"PASS","issues":[
              {"severity":"CRITICAL","segmentId":"scene1","description":"x","suggestion":""}
            ],"minorSuggestions":[]}
            """));
    }

    @Test
    @DisplayName("PASS 带 BLOCKER 被拒收")
    void passWithBlockerIsRejected() {
        assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan("""
            {"result":"PASS","issues":[
              {"severity":"BLOCKER","segmentId":"scene1","description":"x","suggestion":""}
            ],"minorSuggestions":[]}
            """));
    }

    @Test
    @DisplayName("审稿记录落盘带 repairAttempts，能区分一次通过与修出来的")
    void recordCarriesRepairAttempts() {
        var verdict = EditorialReviewer.plan("""
            {"result":"PASS","issues":[],"minorSuggestions":[]}
            """);
        String record = verdict.toRecord(1, "evidence-review",
            java.time.LocalDateTime.parse("2026-11-12T09:00:00"));
        assertTrue(record.contains("\"repairAttempts\":1"), record);
        assertTrue(record.contains("\"result\":\"PASS\""), record);
        assertTrue(record.contains("evidence-review"), record);
    }
}
