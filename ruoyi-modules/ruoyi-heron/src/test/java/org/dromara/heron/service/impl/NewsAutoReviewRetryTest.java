package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审稿失败后该不该换选题重试。
 *
 * <p>只有证据问题值得换：换一条证据更充分的选题可能就过了。
 * 文案问题换选题是浪费（改稿就能救），环境故障换选题避不开（下一个一样失败）。
 */
@DisplayName("自动产出重试判据：只换证据问题")
class NewsAutoReviewRetryTest {

    private static HeronBusinessException code(String value) {
        return new HeronBusinessException(409, value, "test");
    }

    @Test
    @DisplayName("核不了、无据：换选题")
    void evidenceFailuresAreRetryable() {
        assertTrue(NewsAutoService.isEvidenceReviewFailure(code("editorial_review_unverifiable")));
        assertTrue(NewsAutoService.isEvidenceReviewFailure(code("editorial_review_unsupported")));
    }

    /** 文案跑在证据前面：改稿就能救，换选题只是白扔一次生产。 */
    @Test
    @DisplayName("文案问题：不换选题")
    void wordingFailureIsNotRetryable() {
        assertFalse(NewsAutoService.isEvidenceReviewFailure(code("editorial_review_blocked")));
    }

    /** 渲染、TTS、取数、契约这类失败，换选题一样会撞上。 */
    @Test
    @DisplayName("非审稿失败：不换选题")
    void otherFailuresAreNotRetryable() {
        assertFalse(NewsAutoService.isEvidenceReviewFailure(code("render_failed")));
        assertFalse(NewsAutoService.isEvidenceReviewFailure(new IllegalStateException("boom")));
    }
}
