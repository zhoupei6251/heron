package org.dromara.heron.agentscope.agent;

import org.dromara.heron.agentscope.research.ResearchFindings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 的输出契约。
 *
 * <p>为什么值得单独测：L1 曾经「跑成功了、有自然语言结论、但没有结构化决策」，
 * 于是整条 研究 → 决策 → 提升 的链在这里断掉，而表面上一切正常。
 * 这个契约断掉的时候必须能被发现。
 */
@DisplayName("L1 摘要：结构化决策的解析与越权拦截")
class AgentscopeResearchAgentTest {

    @Test
    @DisplayName("按契约回复 OBSERVE：结论落地，理由带出来")
    void parsesObserve() {
        ResearchFindings findings =
            AgentscopeResearchAgent.readSummary("DECISION: OBSERVE\nREASON: 只有两条数据点，趋势不稳", 9L);

        assertTrue(findings.submitted(), "按格式回了却没落结论，决策链就断了");
        assertEquals(ResearchFindings.OBSERVE, findings.decisionType());
        assertEquals("只有两条数据点，趋势不稳", findings.reason());
    }

    @Test
    @DisplayName("按契约回复 REJECT：同样落地")
    void parsesReject() {
        ResearchFindings findings =
            AgentscopeResearchAgent.readSummary("DECISION: REJECT\nREASON: 指标口径不统一，不可比", 9L);

        assertTrue(findings.submitted());
        assertEquals(ResearchFindings.REJECT, findings.decisionType());
    }

    /** 全角冒号和大小写都要认：模型不会总按你写的标点来。 */
    @Test
    @DisplayName("全角冒号与小写也认")
    void toleratesFormatVariants() {
        ResearchFindings findings =
            AgentscopeResearchAgent.readSummary("decision：observe\nreason：样本不足", 9L);

        assertEquals(ResearchFindings.OBSERVE, findings.decisionType());
        assertEquals("样本不足", findings.reason());
    }

    /**
     * L1 没有查过数据，说 PRODUCE 就是越权。宁可没有结论，也不能让它把
     * 一个「值得做」的判断一路送到候选池。
     */
    @Test
    @DisplayName("回 PRODUCE 不采信：L1 没有查数据的资格")
    void refusesProduce() {
        ResearchFindings findings =
            AgentscopeResearchAgent.readSummary("DECISION: PRODUCE\nREASON: 看起来很有价值", 9L);

        assertFalse(findings.submitted(), "L1 的 PRODUCE 必须被丢掉");
        assertNull(findings.decisionType());
    }

    @Test
    @DisplayName("没按格式回就不记，不替模型编结论")
    void ignoresUnstructuredReply() {
        ResearchFindings findings =
            AgentscopeResearchAgent.readSummary("这条机会看起来不错，值得再观察一下。", 9L);

        assertFalse(findings.submitted());
        assertNull(findings.decisionType());
        assertNull(findings.reason());
    }

    @Test
    @DisplayName("空回复不抛异常")
    void toleratesEmptyReply() {
        assertFalse(AgentscopeResearchAgent.readSummary(null, 9L).submitted());
        assertFalse(AgentscopeResearchAgent.readSummary("   ", 9L).submitted());
    }
}
