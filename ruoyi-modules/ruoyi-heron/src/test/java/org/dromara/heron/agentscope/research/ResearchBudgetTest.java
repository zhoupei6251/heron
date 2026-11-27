package org.dromara.heron.agentscope.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预算的两个维度互不干扰：被 token 拒掉的调用不该再吃掉一个工具名额，
 * 拒绝原因只对真正失败的那一次生效。
 */
@DisplayName("研究预算：工具次数与 token 双维闸")
class ResearchBudgetTest {

    @Test
    @DisplayName("工具次数到顶后拒绝，原因码是 tool_budget_exceeded")
    void rejectsAfterToolLimit() {
        ResearchBudget budget = new ResearchBudget(2, 0);

        assertNull(budget.tryAcquireTool());
        assertNull(budget.tryAcquireTool());

        assertEquals(ResearchBudget.TOOL_EXCEEDED, budget.tryAcquireTool());
        assertEquals(2, budget.toolUsed());
        assertTrue(budget.exceeded());
        assertEquals(ResearchBudget.TOOL_EXCEEDED, budget.pollRejection());
    }

    @Test
    @DisplayName("拒绝原因取走即清空，不会被下一次调用重复记上")
    void rejectionIsConsumedOnce() {
        ResearchBudget budget = new ResearchBudget(1, 0);
        assertNull(budget.tryAcquireTool());
        assertEquals(ResearchBudget.TOOL_EXCEEDED, budget.tryAcquireTool());

        assertEquals(ResearchBudget.TOOL_EXCEEDED, budget.pollRejection());
        assertNull(budget.pollRejection());
    }

    @Test
    @DisplayName("token 到顶后拒绝，但不占工具名额")
    void tokenLimitDoesNotConsumeToolSlot() {
        ResearchBudget budget = new ResearchBudget(5, 100);
        budget.addUsage(80, 30);

        assertEquals(ResearchBudget.TOKEN_EXCEEDED, budget.tryAcquireTool());
        assertTrue(budget.exceeded());
        // 一次都没被放行，工具名额还是 0
        assertEquals(0, budget.toolUsed());
        assertEquals(110, budget.totalTokens());
    }

    @Test
    @DisplayName("token 上限 0 表示不设限")
    void zeroTokenLimitMeansUnlimited() {
        ResearchBudget budget = new ResearchBudget(3, 0);
        budget.addUsage(10_000_000, 10_000_000);

        assertNull(budget.tryAcquireTool());
        assertFalse(budget.exceeded());
    }

    @Test
    @DisplayName("取消信号置位后，下一次工具调用被拒且不占工具名额")
    void cancelSignalRejectsTools() {
        boolean[] cancelled = {false};
        ResearchBudget budget = new ResearchBudget(5, 0, java.time.Duration.ofMinutes(5),
            java.time.Clock.systemDefaultZone(), () -> cancelled[0]);
        assertNull(budget.tryAcquireTool());

        cancelled[0] = true;

        assertEquals(ResearchBudget.CANCELLED, budget.tryAcquireTool());
        assertEquals(1, budget.toolUsed(), "被取消拒掉的那次不该多算一个工具名额");
        assertFalse(budget.exceeded(), "取消不是预算到点，不该顺手标 partial");
    }

    @Test
    @DisplayName("时长到点：下一次工具被拒，且标 partial")
    void timeBudgetRejectsTools() {
        // 可推进的时钟：构造时定下 deadline，之后把它拨过 5 分钟
        java.time.Instant[] now = {java.time.Instant.parse("2026-11-13T10:00:00Z")};
        java.time.Clock clock = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneId.of("UTC");
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return now[0];
            }
        };
        ResearchBudget budget = new ResearchBudget(12, 0, java.time.Duration.ofMinutes(5), clock, null);

        assertNull(budget.tryAcquireTool());
        now[0] = now[0].plus(java.time.Duration.ofMinutes(5)).plusSeconds(1);

        assertEquals(ResearchBudget.TIME_EXCEEDED, budget.tryAcquireTool());
        assertTrue(budget.exceeded(), "时长到点也算预算到点");
        assertEquals(1, budget.toolUsed(), "被时长拒掉的那次不该多算一个工具名额");
    }

    @Test
    @DisplayName("未到顶时放行，用量按累加计")
    void accumulatesUsage() {
        ResearchBudget budget = new ResearchBudget(10, 1000);

        budget.addUsage(100, 50);
        budget.addUsage(200, 25);

        assertNull(budget.tryAcquireTool());
        assertEquals(300, budget.inputTokens());
        assertEquals(75, budget.outputTokens());
        assertEquals(375, budget.totalTokens());
        assertFalse(budget.exceeded());
    }
}
