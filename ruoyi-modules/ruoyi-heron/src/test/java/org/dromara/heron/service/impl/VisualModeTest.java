package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.service.impl.shared.production.VisualMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 视觉模式：机器按数据形态定，geo 才走 3D；模型不参与这个判断。 */
class VisualModeTest {

    private static EditorialStory story(String signalType) {
        return new EditorialStory(
            EditorialStory.SCHEMA_VERSION, "numeric_contrast", "八座城市差出一截",
            new EditorialStory.Protagonist("group", "枢纽城市", ""),
            new EditorialStory.Tension("gap", "最高与最低差出两倍", List.of("facts.leader.value")),
            List.of(new EditorialStory.Claim("c1", "最高的是上海", "fact", List.of("facts.leader.value"))),
            new EditorialStory.Payoff("关注你所在的位置", List.of("facts.leader.value")),
            new EditorialStory.Stakes("发货的人", "成本结构不同"),
            List.of(),
            new EditorialStory.Basis(signalType, null, null, null, 1.0d, 8,
                null, null, "a".repeat(64), "router", null));
    }

    @Test
    @DisplayName("geo 形态走 3D：只有它带经纬度，能诚实做空间画面")
    void geoStoryUsesThreeD() {
        assertEquals(VisualMode.THREE_D, VisualMode.of(story("geo")));
    }

    @Test
    @DisplayName("其它形态保持 2D，不因为好看就上三维")
    void otherShapesStayTwoD() {
        assertEquals(VisualMode.TWO_D, VisualMode.of(story("zscore")));
        assertEquals(VisualMode.TWO_D, VisualMode.of(story("change")));
        assertEquals(VisualMode.TWO_D, VisualMode.of(story(null)));
        assertEquals(VisualMode.TWO_D, VisualMode.of(null));
    }

    @Test
    @DisplayName("模板登记认不出来一律按 2D：3D 必须是显式声明")
    void unknownTemplateModeFallsBackToTwoD() {
        assertEquals(VisualMode.THREE_D, VisualMode.ofKey("3d"));
        assertEquals(VisualMode.THREE_D, VisualMode.ofKey(" 3D "));
        assertEquals(VisualMode.TWO_D, VisualMode.ofKey("2d"));
        assertEquals(VisualMode.TWO_D, VisualMode.ofKey(null));
        assertEquals(VisualMode.TWO_D, VisualMode.ofKey("stereo"));
    }
}
