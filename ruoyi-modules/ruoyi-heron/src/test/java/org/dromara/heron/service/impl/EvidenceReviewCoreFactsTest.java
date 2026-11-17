package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解说线审稿的核心断言门禁。
 *
 * <p>堵的是这条路：审稿模型可以把「证据片段不足以核实核心断言」写成一条 MINOR 意见，
 * 同时判 PASS。下游只看 severity 和 result，看到的是一条干净记录，
 * 于是一条核心事实没被任何来源支持的内容就这样进了自动发布。
 *
 * <p>根因不是模型不听话——证据片段本来就不完整，它说「核不了」是诚实的。
 * 问题在于系统把「核不了」当成了「没问题」。所以现在要求它把这件事单独讲出来。
 */
@DisplayName("解说线审稿：核心断言核不了就不能判 PASS")
class EvidenceReviewCoreFactsTest {

    private static String reply(String result, String coreFacts, String issuesJson) {
        return "{\"result\":\"" + result + "\",\"coreFacts\":" + coreFacts
            + ",\"issues\":" + issuesJson + ",\"minorSuggestions\":[]}";
    }

    @Test
    @DisplayName("核心断言核不住却判 PASS：拒收")
    void unverifiableCannotPass() {
        var e = assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan(reply(
            "PASS", "\"unverifiable\"",
            "[{\"severity\":\"MINOR\",\"segmentId\":\"scene_002\",\"description\":\"证据不足，无法核实\",\"suggestion\":\"\"}]"
        ), true));

        assertEquals("editorial_review_invalid", e.getCode());
        assertTrue(e.getMessage().contains("未经证据支持"), e.getMessage());
    }

    @Test
    @DisplayName("核心断言无依据：必须给 BLOCKER，只给 MAJOR 也不够")
    void unsupportedNeedsBlocker() {
        var e = assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan(reply(
            "FAIL", "\"unsupported\"",
            "[{\"severity\":\"MAJOR\",\"segmentId\":\"scene_001\",\"description\":\"无据\",\"suggestion\":\"\"}]"
        ), true));

        assertTrue(e.getMessage().contains("BLOCKER"), e.getMessage());
    }

    @Test
    @DisplayName("核不了必须落成 MAJOR，只有 MINOR 算自相矛盾")
    void unverifiableNeedsMajor() {
        var e = assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan(reply(
            "FAIL", "\"unverifiable\"",
            "[{\"severity\":\"MINOR\",\"segmentId\":\"scene_001\",\"description\":\"核不了\",\"suggestion\":\"\"}]"
        ), true));

        assertTrue(e.getMessage().contains("MAJOR"), e.getMessage());
    }

    @Test
    @DisplayName("缺 coreFacts 直接拒收：不给出这个判断就是没审完")
    void missingCoreFactsIsRejected() {
        var e = assertThrows(HeronBusinessException.class, () -> EditorialReviewer.plan(
            "{\"result\":\"PASS\",\"issues\":[],\"minorSuggestions\":[]}", true));

        assertTrue(e.getMessage().contains("coreFacts"), e.getMessage());
    }

    @Test
    @DisplayName("核心断言有据：正常通过，并把状态写进落盘记录")
    void supportedPasses() {
        var verdict = EditorialReviewer.plan(reply("PASS", "\"supported\"", "[]"), true);

        assertEquals("supported", verdict.coreFacts());
        assertFalse(verdict.blocked());
        assertTrue(verdict.toRecord(0, "evidence-review",
            java.time.LocalDateTime.parse("2026-11-17T09:00:00")).contains("\"coreFacts\":\"supported\""));
    }

    @Test
    @DisplayName("核不了且给了 MAJOR：可以，由修稿或人工接手")
    void unverifiableWithMajorIsAccepted() {
        var verdict = EditorialReviewer.plan(reply(
            "FAIL", "\"unverifiable\"",
            "[{\"severity\":\"MAJOR\",\"segmentId\":\"scene_001\",\"description\":\"核不了\",\"suggestion\":\"\"}]"
        ), true);

        assertTrue(verdict.blocked(), "核不了的核心断言必须挡住发布");
    }

    /** 数据看板线的事实清单由 Java 给，不存在「核不了」，不该被这条规则连坐。 */
    @Test
    @DisplayName("数据看板线不要求 coreFacts")
    void factualLineDoesNotRequireCoreFacts() {
        var verdict = EditorialReviewer.plan(
            "{\"result\":\"PASS\",\"issues\":[],\"minorSuggestions\":[]}", false);

        assertNull(verdict.coreFacts());
        assertFalse(verdict.blocked());
    }

    /**
     * 槽位层靠这两个判据决定「换选题」还是「改稿」：
     * 证据问题改稿救不了，文案问题换选题是浪费。
     */
    @Test
    @DisplayName("证据问题与文案问题可以区分")
    void distinguishesEvidenceFromWording() {
        var unverifiable = EditorialReviewer.plan(reply("FAIL", "\"unverifiable\"",
            "[{\"severity\":\"MAJOR\",\"segmentId\":\"s1\",\"description\":\"核不了\",\"suggestion\":\"\"}]"), true);
        assertTrue(unverifiable.evidenceProblem());
        assertTrue(unverifiable.unverifiable());

        var wording = EditorialReviewer.plan(reply("FAIL", "\"supported\"",
            "[{\"severity\":\"MAJOR\",\"segmentId\":\"s1\",\"description\":\"程度被夸大\",\"suggestion\":\"\"}]"), true);
        assertFalse(wording.evidenceProblem(), "文案问题该走改稿，不该换选题");
        assertFalse(wording.unverifiable());
    }
}
