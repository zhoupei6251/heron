package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动发布门禁：只认落盘的 PASS。
 * 这一层是安全边界——之前 confirmForAuto 直接传 approved 就发布，不查审稿是否发生过。
 */
@DisplayName("自动发布门禁：fail closed")
class EditorialPublishGateTest {

    private static String errorCode(String raw) {
        return assertThrows(HeronBusinessException.class, () -> NewsWorkServiceImpl.checkEditorialPass(raw)).getCode();
    }

    @Test
    @DisplayName("没有审稿记录不能自动发布")
    void missingRecordIsBlocked() {
        assertEquals("review_required", errorCode(null));
        assertEquals("review_required", errorCode(""));
        assertEquals("review_required", errorCode("   "));
    }

    @Test
    @DisplayName("空对象不能当 PASS 混过去")
    void emptyObjectIsBlocked() {
        assertEquals("review_not_passed", errorCode("{}"));
    }

    @Test
    @DisplayName("无法解析的记录不能自动发布")
    void malformedRecordIsBlocked() {
        assertEquals("review_invalid", errorCode("{not json"));
        assertEquals("review_invalid", errorCode("\"just a string\""));
    }

    @Test
    @DisplayName("FAIL 不能自动发布")
    void failIsBlocked() {
        assertEquals("review_not_passed", errorCode("""
            {"result":"FAIL","issues":[
              {"severity":"MAJOR","segmentId":"scene1","description":"x","suggestion":""}
            ]}
            """));
    }

    @Test
    @DisplayName("PASS 但仍带 MAJOR 不能自动发布")
    void passWithMajorIsBlocked() {
        assertEquals("review_not_passed", errorCode("""
            {"result":"PASS","issues":[
              {"severity":"MAJOR","segmentId":"scene1","description":"x","suggestion":""}
            ]}
            """));
    }

    @Test
    @DisplayName("PASS 带 BLOCKER 不能自动发布")
    void passWithBlockerIsBlocked() {
        assertEquals("review_not_passed", errorCode("""
            {"result":"PASS","issues":[
              {"severity":"BLOCKER","segmentId":"scene1","description":"x","suggestion":""}
            ]}
            """));
    }

    @Test
    @DisplayName("问题清单不是数组不能自动发布")
    void nonArrayIssuesIsBlocked() {
        assertEquals("review_invalid", errorCode("""
            {"result":"PASS","issues":"none"}
            """));
    }

    @Test
    @DisplayName("干净的 PASS 才能放行")
    void cleanPassIsAllowed() {
        assertDoesNotThrow(() -> NewsWorkServiceImpl.checkEditorialPass("""
            {"schemaVersion":"1.0","result":"PASS","issues":[],"repairAttempts":0}
            """));
    }

    @Test
    @DisplayName("PASS 只带 MINOR 也放行")
    void passWithOnlyMinorIsAllowed() {
        assertDoesNotThrow(() -> NewsWorkServiceImpl.checkEditorialPass("""
            {"result":"PASS","issues":[
              {"severity":"MINOR","segmentId":"closing","description":"结尾略拖","suggestion":"压短"}
            ],"repairAttempts":1}
            """));
    }
}
