package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.TrendTechniqueCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrendLearningAgentTest {

    @Test
    void requiresAllEvidenceToolsToHaveSucceeded() {
        Set<String> complete = Set.of("read_breakdown", "list_templates", "read_template", "view_frame");
        assertDoesNotThrow(() -> TrendLearningAgent.requireEvidence(complete));
        assertThrows(HeronBusinessException.class, () -> TrendLearningAgent.requireEvidence(
            Set.of("read_breakdown", "list_templates", "read_template")));
    }

    @Test
    void templateFitMustNameARealTemplateOrExplicitlyDeclareNoMatch() {
        TrendTechniqueCard valid = card("news-rank-9x16：适合排行反差");
        assertDoesNotThrow(() -> TrendLearningAgent.validateTemplateFit(valid, "news-rank-9x16"::equals));
        assertDoesNotThrow(() -> TrendLearningAgent.validateTemplateFit(
            card("无完全匹配：news-rank-9x16：缺证据层级"), "news-rank-9x16"::equals));
        assertThrows(IllegalArgumentException.class, () -> TrendLearningAgent.validateTemplateFit(
            card("无完全匹配：news-imaginary-9x16：编造"), "news-rank-9x16"::equals));
        assertThrows(IllegalArgumentException.class, () -> TrendLearningAgent.validateTemplateFit(
            card("news-imaginary-9x16：编造"), "news-rank-9x16"::equals));
    }

    private static TrendTechniqueCard card(String fit) {
        return new TrendTechniqueCard("反常识提问", 0.5, 2.0, "前快后慢", "大字号短句",
            List.of("数字滚动"), "中", List.of("先抛结论"), fit);
    }
}
