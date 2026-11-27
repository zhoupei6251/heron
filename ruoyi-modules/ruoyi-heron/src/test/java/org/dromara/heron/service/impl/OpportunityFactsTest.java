package org.dromara.heron.service.impl;

import org.dromara.heron.agentscope.research.ResearchGrade;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.service.impl.shared.research.OpportunityFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("内容机会事实投影")
class OpportunityFactsTest {

    @Test
    @DisplayName("机会投影保留信号、快照指纹与支持形状")
    void opportunityFactsKeepSignalAndSnapshotFacts() {
        HeronDataOpportunity opportunity = new HeronDataOpportunity();
        opportunity.setId(12L);
        opportunity.setHeadline("机会标题");
        opportunity.setDedupKey("opportunity-key");
        opportunity.setObservedAt(LocalDateTime.of(2026, 11, 25, 9, 0));
        opportunity.setPoints(9);
        opportunity.setWindowPoints(10);
        opportunity.setShape("wide");
        opportunity.setCadence("daily");
        opportunity.setSignals("""
            {"coverage":0.9,"zscore":-2.0,"extreme":true,"rank_delta":-4,"merged_count":3}
            """);
        opportunity.setSnapshotJson("""
            {"data_sha256":"abc123","cadence":"daily"}
            """);

        OpportunityFacts facts = OpportunityFacts.fromOpportunity(opportunity, "line, wide");

        assertEquals("opportunity:12", facts.refKey());
        assertEquals("opportunity", facts.sourceType());
        assertEquals("机会标题", facts.normalizedTitle());
        assertEquals(0.9, facts.coverage());
        assertEquals(9, facts.points());
        assertEquals(10, facts.windowPoints());
        assertTrue(facts.hasDataSha256());
        assertEquals(3, facts.mergedSourceCount());
        assertEquals(-2.0, facts.zscore());
        assertTrue(facts.extreme());
        assertEquals(-4, facts.rankDelta());
        assertEquals("wide", facts.shape());
        assertEquals("daily", facts.cadence());
        assertEquals(java.util.List.of("line", "wide"), facts.supportedShapes());
    }

    @Test
    @DisplayName("机会坏 JSON 不抛，信号缺失但实体事实仍保留")
    void opportunityFactsTolerateMalformedSignalJson() {
        HeronDataOpportunity opportunity = new HeronDataOpportunity();
        opportunity.setId(12L);
        opportunity.setHeadline("机会标题");
        opportunity.setPoints(8);
        opportunity.setWindowPoints(10);
        opportunity.setShape("rows");
        opportunity.setCadence("daily");
        opportunity.setSignals("{bad json");
        opportunity.setSnapshotJson("also bad");

        OpportunityFacts facts = assertDoesNotThrow(
            () -> OpportunityFacts.fromOpportunity(opportunity, "rank"));

        assertNull(facts.coverage());
        assertFalse(facts.hasDataSha256());
        assertEquals(8, facts.points());
        assertEquals("rows", facts.shape());
        assertEquals("daily", facts.cadence());
        assertEquals(1, facts.mergedSourceCount());
    }

    @Test
    @DisplayName("候选坏 JSON 不抛，按缺失证据处理")
    void candidateFactsTolerateMalformedEvidenceJson() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("标题");
        candidate.setEvidence("{bad json");

        OpportunityFacts facts = assertDoesNotThrow(() -> OpportunityFacts.fromCandidate(candidate, "article"));

        assertEquals(0, facts.evidenceCount());
        assertEquals(0, facts.bodyLength());
        assertEquals(0, facts.sourceCount());
    }

    @Test
    @DisplayName("研究置信度按百分比复用 ResearchGrade，缺失退回 L1")
    void researchFactsReuseResearchGradeThresholds() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("研究标题");
        candidate.setEvidence("""
            {"items":[{"confidence":"0.62"}]}
            """);

        assertEquals(ResearchGrade.L2,
            OpportunityFacts.fromCandidate(candidate, "research").researchGrade());
        candidate.setEvidence("""
            {"items":[{"confidence":null}]}
            """);
        assertEquals(ResearchGrade.L1,
            OpportunityFacts.fromCandidate(candidate, "research").researchGrade());
    }

    @Test
    @DisplayName("证据 JSON 提取正文长度、证据条数与去重源数")
    void candidateFactsExtractEvidenceShape() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("标题");
        candidate.setEvidence("""
            {"items":[
              {"sourceUrl":"https://a.test/1","body":"😀x"},
              {"sourceUrl":"https://a.test/1","content":"abc"},
              {"sourceUrl":"https://b.test/1"},
              {"sourceKey":"source-c"}
            ]}
            """);

        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "article");

        assertEquals(4, facts.evidenceCount());
        assertEquals(3, facts.sourceCount());
        assertEquals(3, facts.bodyLength(), "正文按 code point 计长，并取最长证据正文");
    }

    @Test
    @DisplayName("文章正文落在 evidence.items[].summary，按最长文本计入正文长度")
    void candidateFactsUseItemSummaryAsBody() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("标题");
        candidate.setEvidence("""
            {"items":[{"summary":"短"},{"summary":"一二三四五六","body":""}]}
            """);

        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "article");

        assertEquals(6, facts.bodyLength(), "采集没有独立 body 字段，正文在 items[].summary");
    }

    @Test
    @DisplayName("候选标量事实保留观察时间、摘要长度与热榜信号")
    void candidateFactsKeepScalarSignals() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("标题");
        candidate.setSummary("abcdef");
        candidate.setLastSeenAt(LocalDateTime.of(2026, 11, 25, 8, 0));
        candidate.setHeatScore(99L);
        candidate.setBestRank(3);

        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");

        assertEquals(LocalDateTime.of(2026, 11, 25, 8, 0), facts.observedAt());
        assertEquals(6, facts.summaryLength());
        assertEquals(99L, facts.heatScore());
        assertEquals(3, facts.bestRank());
    }

    @Test
    @DisplayName("候选引用带类型前缀，并按真实标题归一")
    void candidateFactsUseTypedRefKeyAndNormalizedTitle() {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("Ａ，测试");

        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");

        assertEquals("candidate:12", facts.refKey());
        assertEquals("hot", facts.sourceType());
        assertEquals("Ａ，测试", facts.label());
        assertEquals("a测试", facts.normalizedTitle());
    }
}
