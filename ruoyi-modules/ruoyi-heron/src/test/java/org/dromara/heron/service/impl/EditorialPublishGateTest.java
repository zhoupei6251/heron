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
    @DisplayName("问题清单缺失不能当「零问题」放过")
    void missingIssuesIsBlocked() {
        // 之前 isMissingNode 直接跳过校验，一个 {"result":"PASS"} 就能自动发布
        assertEquals("review_invalid", errorCode("""
            {"result":"PASS"}
            """));
        assertEquals("review_invalid", errorCode("""
            {"schemaVersion":"1.0","result":"PASS","repairAttempts":0}
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
    @DisplayName("PASS 只带 MINOR 也放行（当前契约记录）")
    void passWithOnlyMinorIsAllowed() {
        assertDoesNotThrow(() -> NewsWorkServiceImpl.checkEditorialPass("""
            {"schemaVersion":"1.0","result":"PASS","issues":[
              {"severity":"MINOR","segmentId":"closing","description":"结尾略拖","suggestion":"压短"}
            ],"repairAttempts":1}
            """));
    }

    @Test
    @DisplayName("缺 schemaVersion 的旧记录：不能当 PASS 自动发布")
    void legacyRecordWithoutSchemaIsBlocked() {
        assertEquals("review_legacy", errorCode("""
            {"result":"PASS","issues":[
              {"severity":"MINOR","segmentId":"closing","description":"结尾略拖","suggestion":"压短"}
            ],"repairAttempts":1}
            """));
        assertEquals("review_legacy", errorCode("""
            {"result":"PASS","issues":[],"repairAttempts":0}
            """));
    }

    @Test
    @DisplayName("未知或缺失的严重级别：不能默认当低风险放行")
    void unknownSeverityIsBlocked() {
        // 拼错的 BLOCKER、小写写法、自造级别，过去都被静默放过
        for (String severity : new String[]{"SEVERE", "blocker", "Major"}) {
            assertEquals("review_invalid", errorCode("""
                {"schemaVersion":"1.0","result":"PASS","issues":[
                  {"severity":"%s","segmentId":"scene1","description":"x","suggestion":""}
                ]}
                """.formatted(severity)), severity + " 必须被拒");
        }
        assertEquals("review_invalid", errorCode("""
            {"schemaVersion":"1.0","result":"PASS","issues":[
              {"segmentId":"scene1","description":"x","suggestion":""}
            ]}
            """));
        assertEquals("review_invalid", errorCode("""
            {"schemaVersion":"1.0","result":"PASS","issues":[
              {"severity":"","segmentId":"scene1","description":"x","suggestion":""}
            ]}
            """));
    }

    /**
     * 解说线的依据是证据片段，核不了的核心断言不能发。
     * 上游解析已经要求过一遍，这里是落盘记录上的第二道闸——
     * 旧记录没有这个字段同样不放行，「没写」不等于「核过」。
     */
    @Test
    @DisplayName("解说线记录没确认核心断言有据：不能自动发布")
    void evidenceReviewWithoutSupportedCoreFactsIsBlocked() {
        assertEquals("review_core_facts_unsupported", errorCode("""
            {"schemaVersion":"1.0","result":"PASS","promptVersion":"evidence-review",
             "issues":[],"repairAttempts":0}
            """));
        assertEquals("review_core_facts_unsupported", errorCode("""
            {"schemaVersion":"1.0","result":"PASS","promptVersion":"evidence-review",
             "coreFacts":"unverifiable","issues":[],"repairAttempts":0}
            """));
        assertEquals("review_core_facts_unsupported", errorCode("""
            {"schemaVersion":"1.0","result":"PASS","promptVersion":"evidence-review",
             "coreFacts":"unsupported","issues":[],"repairAttempts":0}
            """));
    }

    @Test
    @DisplayName("解说线记录确认核心断言有据：放行")
    void evidenceReviewWithSupportedCoreFactsPasses() {
        assertDoesNotThrow(() -> NewsWorkServiceImpl.checkEditorialPass("""
            {"schemaVersion":"1.0","result":"PASS","promptVersion":"evidence-review",
             "coreFacts":"supported","issues":[],"repairAttempts":0}
            """));
    }

    /** 数据看板线的事实清单由 Java 给，不存在「核不了」，不该被这条规则连坐。 */
    @Test
    @DisplayName("数据看板线记录不要求 coreFacts")
    void editorialReviewDoesNotRequireCoreFacts() {
        assertDoesNotThrow(() -> NewsWorkServiceImpl.checkEditorialPass("""
            {"schemaVersion":"1.0","result":"PASS","promptVersion":"editorial-review",
             "issues":[],"repairAttempts":0}
            """));
    }
}
