package org.dromara.heron.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrendTechniqueCardTest {

    private static final String CARD = """
        {
          "hookType": "反常识提问",
          "firstShotSeconds": 0.8,
          "avgShotSeconds": 2.4,
          "cutRhythm": "前快后慢",
          "textStyle": "大字号短句",
          "motionTypes": ["数字滚动", "左侧滑入"],
          "infoDensity": "中",
          "reusablePoints": ["先抛结论再给证据", "用排名变化制造反差"],
          "heronTemplateFit": "news-rank-9x16：适合排行反差，需补证据层级"
        }
        """;

    @Test
    void parsesFixedTechniqueCardContract() {
        TrendTechniqueCard card = TrendTechniqueCard.parse(CARD);

        assertEquals("反常识提问", card.hookType());
        assertEquals(0.8, card.firstShotSeconds());
        assertEquals(2.4, card.avgShotSeconds());
        assertEquals("前快后慢", card.cutRhythm());
        assertEquals(List.of("数字滚动", "左侧滑入"), card.motionTypes());
        assertEquals(List.of("先抛结论再给证据", "用排名变化制造反差"), card.reusablePoints());
        assertEquals("news-rank-9x16：适合排行反差，需补证据层级", card.heronTemplateFit());
    }

    @Test
    void acceptsJsonWrappedInMarkdownFence() {
        assertEquals("大字号短句", TrendTechniqueCard.parse("```json\n" + CARD + "\n```").textStyle());
    }

    @Test
    void rejectsCardWithMissingRequiredField() {
        String incomplete = CARD.replace("  \"cutRhythm\": \"前快后慢\",\n", "");
        assertThrows(IllegalArgumentException.class, () -> TrendTechniqueCard.parse(incomplete));
    }

    @Test
    void rejectsUnknownTemplateClaimWithoutReason() {
        String invalid = CARD.replace("news-rank-9x16：适合排行反差，需补证据层级", "");
        assertThrows(IllegalArgumentException.class, () -> TrendTechniqueCard.parse(invalid));
    }
}
