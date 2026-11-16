package org.dromara.heron.agentscope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分级决定的是成本曲线，所以每个边界都值得钉住：
 * 没评分不该花大钱、只有最高档才核验、认不出的等级码不该静默降到最便宜那档。
 */
@DisplayName("分析分级：按评分定档与各档能力")
class ResearchGradeTest {

    @Test
    @DisplayName("按机会评分定档，边界值归到高档")
    void gradesByScore() {
        assertEquals(ResearchGrade.L1, ResearchGrade.of(null), "没评分按最低档");
        assertEquals(ResearchGrade.L1, ResearchGrade.of(0.0));
        assertEquals(ResearchGrade.L1, ResearchGrade.of(ResearchGrade.L2_THRESHOLD - 0.01));
        assertEquals(ResearchGrade.L2, ResearchGrade.of(ResearchGrade.L2_THRESHOLD));
        assertEquals(ResearchGrade.L2, ResearchGrade.of(ResearchGrade.L3_THRESHOLD - 0.01));
        assertEquals(ResearchGrade.L3, ResearchGrade.of(ResearchGrade.L3_THRESHOLD));
        assertEquals(ResearchGrade.L3, ResearchGrade.of(100.0));
    }

    @Test
    @DisplayName("档位能力：L1 不查工具、只有 L3 核验")
    void capabilities() {
        assertFalse(ResearchGrade.L1.usesTools());
        assertTrue(ResearchGrade.L2.usesTools());
        assertTrue(ResearchGrade.L3.usesTools());

        assertFalse(ResearchGrade.L1.verifies());
        assertFalse(ResearchGrade.L2.verifies());
        assertTrue(ResearchGrade.L3.verifies(), "核验要再花一次完整 Agent 调用，只该给最高档");

        assertTrue(ResearchGrade.L2.usesReActOnly());
        assertFalse(ResearchGrade.L3.usesReActOnly());
    }

    @Test
    @DisplayName("预算倍数随档递增")
    void budgetFactors() {
        assertTrue(ResearchGrade.L1.budgetFactor() < ResearchGrade.L2.budgetFactor());
        assertTrue(ResearchGrade.L2.budgetFactor() < ResearchGrade.L3.budgetFactor());
    }

    @Test
    @DisplayName("L1 的时长上限比其它档短")
    void l1HasShorterWindow() {
        Duration base = Duration.ofMinutes(5);

        assertEquals(base, ResearchGrade.L2.maxDuration(base));
        assertEquals(base, ResearchGrade.L3.maxDuration(base));
        assertTrue(ResearchGrade.L1.maxDuration(base).compareTo(base) < 0);
    }

    @Test
    @DisplayName("解析等级码；认不出来退回 L2 而不是最便宜的一档")
    void parseFallsBackToL2() {
        assertEquals(ResearchGrade.L1, ResearchGrade.parse("L1"));
        assertEquals(ResearchGrade.L3, ResearchGrade.parse(" l3 "));
        assertEquals(ResearchGrade.L2, ResearchGrade.parse(null));
        assertEquals(ResearchGrade.L2, ResearchGrade.parse("L9"), "认不出就退到有工具但不核验，别静默降级");
    }
}
