package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("内容机会硬门禁")
class OpportunityGateTest {

    @Test
    @DisplayName("合规词大小写不敏感命中，空白词忽略")
    void complianceKeywordBlocksCaseInsensitively() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("Market MANIPULATION 内幕");
        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");

        OpportunityScore.GateResult result = OpportunityGate.evaluate(facts, List.of(),
            java.util.List.of("  ", "manipulation"));

        assertEquals(OpportunityScore.Gate.BLOCK, result.gate());
        assertTrue(result.blockReason().contains("compliance_risk"));
        assertTrue(result.blockReason().contains("manipulation"));
    }

    @Test
    @DisplayName("机会覆盖率或点数不足触发样本闸，原因带实际/请求点数")
    void insufficientOpportunitySampleBlocksWithPointCounts() {
        OpportunityScore.GateResult lowCoverage = OpportunityGate.evaluate(
            opportunity(0.4, 10, 10), List.of(), List.of());
        OpportunityScore.GateResult lowPoints = OpportunityGate.evaluate(
            opportunity(0.9, 7, 10), List.of(), List.of());

        assertEquals(OpportunityScore.Gate.BLOCK, lowCoverage.gate());
        assertTrue(lowCoverage.blockReason().contains("sample_insufficient"));
        assertTrue(lowCoverage.blockReason().contains("10/10"));
        assertTrue(lowCoverage.blockReason().contains("0.4"));
        assertEquals(OpportunityScore.Gate.BLOCK, lowPoints.gate());
        assertTrue(lowPoints.blockReason().contains("7/10"));
    }

    @Test
    @DisplayName("标题 bigram Jaccard 达到 0.8 触发重复，低于阈值放行")
    void titleBigramThresholdBlocksOnlyNearDuplicates() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("abcdefghij");
        candidate.setDedupKey("new-key");
        candidate.setLastSeenAt(LocalDateTime.of(2026, 11, 25, 12, 0));
        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");

        OpportunityScore.GateResult blocked = OpportunityGate.evaluate(facts,
            List.of(publication(77L, "abcdefghik", "old-key", facts.observedAt())), List.of());
        OpportunityScore.GateResult passed = OpportunityGate.evaluate(facts,
            List.of(publication(78L, "abcdefgh", "old-key", facts.observedAt())), List.of());

        assertEquals(OpportunityScore.Gate.BLOCK, blocked.gate());
        assertTrue(blocked.blockReason().contains("77"));
        assertEquals(OpportunityScore.Gate.PASS, passed.gate());
    }

    @Test
    @DisplayName("近 14 天相同 dedupKey 触发重复闸并留版本证据")
    void duplicateKeyWithinFourteenDaysBlocksWithPublicationEvidence() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("新标题");
        candidate.setDedupKey("same-key");
        candidate.setLastSeenAt(LocalDateTime.of(2026, 11, 25, 12, 0));
        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");
        List<OpportunityFacts.Publication> publications = List.of(
            new OpportunityFacts.Publication(77L, "已发布标题", "same-key",
                LocalDateTime.of(2026, 11, 20, 12, 0)));

        OpportunityScore.GateResult result = OpportunityGate.evaluate(facts, publications, List.of());

        assertEquals(OpportunityScore.Gate.BLOCK, result.gate());
        assertTrue(result.blockReason().contains("duplicate_recent"));
        assertTrue(result.blockReason().contains("77"));
        assertTrue(result.blockReason().contains("已发布标题"));
    }

    @Test
    @DisplayName("候选 12 与机会 12 是两条对象：同一 dedupKey 只拦候选，不误伤机会")
    void sameIdDifferentTypesDoNotCollide() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("热榜标题");
        candidate.setDedupKey("shared-key");
        candidate.setLastSeenAt(LocalDateTime.of(2026, 11, 25, 12, 0));
        OpportunityFacts candidateFacts = OpportunityFacts.fromCandidate(candidate, "hot");
        OpportunityFacts opportunityFacts = opportunity(0.9, 10, 10);
        List<OpportunityFacts.Publication> publications = List.of(publication(77L, "已发布标题",
            "shared-key", LocalDateTime.of(2026, 11, 20, 12, 0)));

        assertEquals("candidate:12", candidateFacts.refKey());
        assertEquals("opportunity:12", opportunityFacts.refKey());
        assertEquals(OpportunityScore.Gate.BLOCK,
            OpportunityGate.evaluate(candidateFacts, publications, List.of()).gate());
        assertEquals(OpportunityScore.Gate.PASS,
            OpportunityGate.evaluate(opportunityFacts, publications, List.of()).gate());
    }

    @Test
    @DisplayName("只有标题的热榜候选不被证据类规则打死")
    void titleOnlyHotCandidatePassesEvidenceRules() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("只有一个标题");
        candidate.setLastSeenAt(LocalDateTime.of(2026, 11, 25, 12, 0));

        OpportunityScore.GateResult result = OpportunityGate.evaluate(
            OpportunityFacts.fromCandidate(candidate, "hot"), List.of(), List.of());

        assertEquals(OpportunityScore.Gate.PASS, result.gate());
    }

    @Test
    @DisplayName("候选几天前就进池时，重复窗口锚在评分当下，不漏这期间发布的作品")
    void duplicateWindowAnchorsOnScoringTime() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("新标题");
        candidate.setDedupKey("same-key");
        candidate.setLastSeenAt(now.minusDays(30));
        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");
        List<OpportunityFacts.Publication> publications = List.of(publication(77L, "已发布标题",
            "same-key", now.minusDays(1)));

        OpportunityScore.GateResult result = OpportunityGate.evaluate(facts, publications,
            List.of(), now);

        assertEquals(OpportunityScore.Gate.BLOCK, result.gate());
        assertTrue(result.blockReason().contains("77"));
    }

    private static OpportunityFacts opportunity(double coverage, int points, int windowPoints) {
        HeronDataOpportunity opportunity = new HeronDataOpportunity();
        opportunity.setId(12L);
        opportunity.setHeadline("机会标题");
        opportunity.setObservedAt(LocalDateTime.of(2026, 11, 25, 12, 0));
        opportunity.setPoints(points);
        opportunity.setWindowPoints(windowPoints);
        opportunity.setSignals("{\"coverage\":" + coverage + "}");
        return OpportunityFacts.fromOpportunity(opportunity, "wide");
    }

    private static OpportunityFacts.Publication publication(long id, String title, String dedupKey,
                                                             LocalDateTime publishedAt) {
        return new OpportunityFacts.Publication(id, title, dedupKey, publishedAt);
    }
}
