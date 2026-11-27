package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.service.impl.shared.research.OpportunityFacts;
import org.dromara.heron.service.impl.shared.research.OpportunityScore;
import org.dromara.heron.service.impl.shared.research.OpportunityScorer.OpportunityScoringConfig;
import org.dromara.heron.service.impl.shared.research.OpportunityScorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("机会评分值对象")
class OpportunityScorerTest {

    @Test
    @DisplayName("默认权重对仅标题热榜打分：证据固定 30，张力与视觉无信号取 0，总分四舍五入")
    void defaultWeightsScoreTitleOnlyHotCandidate() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
        OpportunityFacts facts = hotFacts(now);

        OpportunityScore score = OpportunityScorer.score(facts,
            new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null), List.of(),
            OpportunityScoringConfig.defaults(now));

        assertEquals("candidate:12", score.refKey());
        assertEquals("hot", score.sourceType());
        assertEquals("热榜标题", score.label());
        // 热榜只有标题：没有 z/extreme/rank_delta 就没有张力底分，没有 shape 就没有视觉底分，
        // 这两维等阶段 2 模型上浮；证据维固定 30 防止「只有标题」被证据规则打死。
        assertEquals(new OpportunityScore.Dimensions(30, 100, 100, 50, 0, 0), score.dimensions());
        assertEquals(44, score.total());
        assertEquals(OpportunityScore.Gate.PASS, score.gate());
    }

    @Test
    @DisplayName("同一输入两次打分，三个确定性维度逐项一致")
    void deterministicDimensionsAreStable() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
        var publications = List.of(new OpportunityFacts.Publication(1L, "abcdefghik", "key-1",
            now.minusDays(1)));

        OpportunityScore first = OpportunityScorer.score(hotFacts(now),
            new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null), publications,
            OpportunityScoringConfig.defaults(now));
        OpportunityScore second = OpportunityScorer.score(hotFacts(now),
            new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null), publications,
            OpportunityScoringConfig.defaults(now));

        assertEquals(first.dimensions().evidence(), second.dimensions().evidence());
        assertEquals(first.dimensions().novelty(), second.dimensions().novelty());
        assertEquals(first.dimensions().timeliness(), second.dimensions().timeliness());
        assertEquals(first.total(), second.total());
    }

    @Test
    @DisplayName("新颖度取近 14 天最大标题相似度的补数")
    void noveltyUsesMaximumRecentTitleSimilarity() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("abcdefghij");
        candidate.setLastSeenAt(now);
        OpportunityFacts facts = OpportunityFacts.fromCandidate(candidate, "hot");
        var publications = List.of(
            new OpportunityFacts.Publication(1L, "abcdefghik", "key-1", now.minusDays(1)),
            new OpportunityFacts.Publication(2L, "完全不同标题", "key-2", now.minusDays(2)));

        OpportunityScore score = OpportunityScorer.score(facts,
            new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null), publications,
            OpportunityScoringConfig.defaults(now));

        assertEquals(20, score.dimensions().novelty());
    }

    @Test
    @DisplayName("时效按 24 小时指数衰减，知识与非新闻节奏机会取 50")
    void timelinessDecaysAndEvergreenSourcesUseFloor() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("标题");
        candidate.setLastSeenAt(now.minusHours(24));
        OpportunityFacts oldHot = OpportunityFacts.fromCandidate(candidate, "hot");
        OpportunityFacts knowledge = OpportunityFacts.fromCandidate(candidate, "knowledge");
        OpportunityScore.GateResult pass = new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null);

        assertEquals(37, OpportunityScorer.score(oldHot, pass, List.of(),
            OpportunityScoringConfig.defaults(now)).dimensions().timeliness());
        assertEquals(50, OpportunityScorer.score(knowledge, pass, List.of(),
            OpportunityScoringConfig.defaults(now)).dimensions().timeliness());
    }

    @Test
    @DisplayName("权重可覆写，单维权重时总分等于该维分数")
    void customWeightsReplaceDefaultDistribution() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
        OpportunityScoringConfig config = new OpportunityScoringConfig(0, 0, 0, 100, 0, 0, now);

        OpportunityScore score = OpportunityScorer.score(hotFacts(now),
            new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null), List.of(), config);

        assertEquals(50, score.total());
    }

    @Test
    @DisplayName("权重总和为零必须拒绝")
    void zeroWeightSumIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new OpportunityScoringConfig(0, 0, 0, 0, 0, 0,
                LocalDateTime.of(2026, 11, 25, 12, 0)));
    }

    private static OpportunityFacts hotFacts(LocalDateTime now) {
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(12L);
        candidate.setTitle("热榜标题");
        candidate.setLastSeenAt(now);
        return OpportunityFacts.fromCandidate(candidate, "hot");
    }

    @Test
    @DisplayName("总分越界必须拒绝，不能出现非法机会分")
    void totalRejectsOutOfRangeValues() {
        var dimensions = new OpportunityScore.Dimensions(50, 50, 50, 50, 50, 50);
        assertThrows(IllegalArgumentException.class,
            () -> new OpportunityScore("candidate:1", "hot", "标题", -1,
                dimensions, OpportunityScore.Gate.PASS, null));
        assertThrows(IllegalArgumentException.class,
            () -> new OpportunityScore("candidate:1", "hot", "标题", 101,
                dimensions, OpportunityScore.Gate.PASS, null));
    }

    @Test
    @DisplayName("评分维度越界必须拒绝，不能静默截断")
    void dimensionsRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new OpportunityScore.Dimensions(-1, 50, 50, 50, 50, 50));
        assertThrows(IllegalArgumentException.class,
            () -> new OpportunityScore.Dimensions(50, 101, 50, 50, 50, 50));
    }
}
