package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 成本概览的可信度取决于两点：分档均值不能把 L1 和 L3 混在一起算，
 * 采纳率的分母不能是「所有研究」——没出决策的研究不该拉低采纳率。
 */
@DisplayName("成本概览：分档均值与采纳率口径")
class ResearchCostServiceTest {

    @SuppressWarnings("unchecked")
    private static ResearchCostService service(HeronResearchTaskMapper tasks, HeronContentDecisionMapper decisions) {
        return service(tasks, decisions, new ModelPriceConfig(key -> null));
    }

    @SuppressWarnings("unchecked")
    private static ResearchCostService service(HeronResearchTaskMapper tasks, HeronContentDecisionMapper decisions,
                                               ModelPriceConfig prices) {
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);
        return new ResearchCostService(tasks, decisions, prices, clocks);
    }

    private static HeronResearchTask task(String grade, long in, long out, String decision) {
        HeronResearchTask row = new HeronResearchTask();
        row.setGrade(grade);
        row.setInputTokens(in);
        row.setOutputTokens(out);
        row.setDecisionType(decision);
        row.setToolCalls(3);
        row.setStatus(HeronResearchTask.STATUS_SUCCEEDED);
        return row;
    }

    private static HeronContentDecision decision(String type, String status, String verdict) {
        HeronContentDecision row = new HeronContentDecision();
        row.setDecisionType(type);
        row.setStatus(status);
        row.setVerificationVerdict(verdict);
        return row;
    }

    @Test
    @DisplayName("分档均值分开算：L1 与 L3 不能混成一个平均")
    void averagesByGrade() {
        HeronResearchTaskMapper tasks = mock(HeronResearchTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of(
            task("L1", 10, 10, null),
            task("L3", 1000, 500, "PRODUCE")));
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectList(any())).thenReturn(List.of());

        var vo = service(tasks, decisions).overview(30);

        assertEquals(2, vo.getResearchCount());
        var l1 = vo.getByGrade().stream().filter(g -> "L1".equals(g.grade())).findFirst().orElseThrow();
        var l3 = vo.getByGrade().stream().filter(g -> "L3".equals(g.grade())).findFirst().orElseThrow();
        assertEquals(20, l1.avgTokens());
        assertEquals(1500, l3.avgTokens(), "L3 的均值不该被 L1 拉低");
    }

    @Test
    @DisplayName("采纳率的分母是已出决策的研究数，不是全部研究")
    void acceptanceRateOverDecided() {
        HeronResearchTaskMapper tasks = mock(HeronResearchTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of(
            task("L2", 1, 1, "PRODUCE"),
            task("L2", 1, 1, null),      // 没出决策，不该拉低采纳率
            task("L2", 1, 1, "REJECT")));
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectList(any())).thenReturn(List.of(
            decision("PRODUCE", HeronContentDecision.STATUS_ACCEPTED, "AGREE"),
            decision("REJECT", HeronContentDecision.STATUS_PROPOSED, null)));

        var vo = service(tasks, decisions).overview(30);

        assertEquals(2, vo.getDecidedCount(), "只有出了 decision_type 的才算已决策");
        assertEquals(new BigDecimal("0.5000"), vo.getAcceptanceRate());
        assertEquals(1, vo.getVerifiedCount());
        assertEquals(0, vo.getConflictCount());
    }

    @Test
    @DisplayName("冲突数只数 DISAGREE，UNCERTAIN 不算")
    void conflictCountsOnlyDisagree() {
        HeronResearchTaskMapper tasks = mock(HeronResearchTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of());
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectList(any())).thenReturn(List.of(
            decision("PRODUCE", HeronContentDecision.STATUS_PROPOSED, "DISAGREE"),
            decision("PRODUCE", HeronContentDecision.STATUS_PROPOSED, "UNCERTAIN"),
            decision("PRODUCE", HeronContentDecision.STATUS_PROPOSED, "AGREE")));

        var vo = service(tasks, decisions).overview(30);

        assertEquals(3, vo.getVerifiedCount());
        assertEquals(1, vo.getConflictCount(), "UNCERTAIN 说数据不够，不是冲突");
    }

    @Test
    @DisplayName("空库不除零，采纳率为 null")
    void handlesEmpty() {
        HeronResearchTaskMapper tasks = mock(HeronResearchTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of());
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectList(any())).thenReturn(List.of());

        var vo = service(tasks, decisions).overview(0);

        assertEquals(30, vo.getDays(), "非法窗口回落到 30 天");
        assertEquals(0, vo.getAvgTokens());
        assertNull(vo.getAcceptanceRate());
    }

    @Test
    @DisplayName("已知 token 与单价，折算金额与手算一致")
    void amountMultipliesTokensByPrice() {
        HeronResearchTaskMapper tasks = mock(HeronResearchTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of(
            task("L2", 120_000, 40_000, "PRODUCE"),
            task("L1", 80_000, 10_000, null)));
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectList(any())).thenReturn(List.of());
        // 0.002 元 / 千输入、0.006 元 / 千输出
        ModelPriceConfig prices = new ModelPriceConfig(key -> switch (key) {
            case ModelPriceConfig.PRICE_IN_KEY -> "0.002";
            case ModelPriceConfig.PRICE_OUT_KEY -> "0.006";
            default -> null;
        });

        var vo = service(tasks, decisions, prices).overview(30);

        // (200000 × 0.002 + 50000 × 0.006) / 1000 = (400 + 300) / 1000 = 0.7000
        assertEquals(new BigDecimal("0.7000"), vo.getAmount());
        assertTrue(vo.isPriced());
    }

    @Test
    @DisplayName("单价没配时金额为 0，并明确标记这个数不可信")
    void unpricedOverviewSaysSo() {
        HeronResearchTaskMapper tasks = mock(HeronResearchTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of(task("L2", 120_000, 40_000, "PRODUCE")));
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectList(any())).thenReturn(List.of());

        var vo = service(tasks, decisions).overview(30);

        assertEquals(0, vo.getAmount().signum(), "没配单价不该凭空给出金额");
        assertFalse(vo.isPriced(), "0 元不等于没花钱，界面要能看出这个数不可信");
    }
}
