package org.dromara.heron.service.impl.shared.research;

import org.dromara.heron.agentscope.research.ResearchGrade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** 阶段 1 确定性评分：纯函数，不接模型、不写库。受众维等模型，先取中性 50。 */
public final class OpportunityScorer {

    private OpportunityScorer() {
    }

    /**
     * 计算一条事实的六维加权总分。纯函数：不调模型、不写库，受众维固定取中性 50。
     *
     * @param gate               硬门禁结论，原样带进评分视图
     * @param recentPublications 用于新颖度计算的已发布作品；可为 null
     * @param config             各维权重与时间锚点
     * @return 0–100 的总分、六维明细与门禁结论
     */
    public static OpportunityScore score(OpportunityFacts facts, OpportunityScore.GateResult gate,
                                         List<OpportunityFacts.Publication> recentPublications,
                                         OpportunityScoringConfig config) {
        OpportunityScore.Dimensions dimensions = new OpportunityScore.Dimensions(
            evidence(facts),
            novelty(facts, recentPublications, config.now()),
            timeliness(facts, config.now()),
            50,
            tension(facts),
            visual(facts));
        int total = (int) Math.round(weighted(dimensions, config));
        return new OpportunityScore(facts.refKey(), facts.sourceType(), facts.label(), total,
            dimensions, gate.gate(), gate.blockReason());
    }

    /**
     * 证据维：热榜固定 30；正文按长度和条数，知识按摘要和源数，研究按档，机会按覆盖率、指纹和源数。
     * 正文最多计 800 字、证据最多 3 条，避免长文把这一维打满后盖过别的来源。
     */
    private static int evidence(OpportunityFacts facts) {
        return switch (facts.sourceType() == null ? "" : facts.sourceType()) {
            case "hot" -> 30;
            case "article" -> scale(facts.bodyLength(), 800, 70) + scaleCount(facts.evidenceCount(), 3, 10);
            case "knowledge" -> scale(facts.summaryLength(), 200, 70) + scaleCount(facts.sourceCount(), 3, 10);
            case "research" -> switch (facts.researchGrade() == null ? ResearchGrade.L1 : facts.researchGrade()) {
                case L1 -> 40;
                case L2 -> 70;
                case L3 -> 100;
            };
            case "opportunity" -> opportunityEvidence(facts);
            default -> 0;
        };
    }

    /** 没有独立源等级字段，用合并源数代替：1 个 0.6，2 个 0.8，3 个及以上 1。 */
    private static int opportunityEvidence(OpportunityFacts facts) {
        double coverage = facts.coverage() == null || !Double.isFinite(facts.coverage()) ? 0 : facts.coverage();
        coverage = Math.clamp(coverage, 0, 1);
        double fingerprint = facts.hasDataSha256() ? 1 : 0.5;
        int sources = facts.mergedSourceCount();
        double grade = sources >= 3 ? 1 : sources == 2 ? 0.8 : 0.6;
        return (int) Math.round(coverage * fingerprint * grade * 100);
    }

    private static int scale(int value, int cap, int points) {
        return cap == 0 ? 0 : Math.clamp(value, 0, cap) * points / cap;
    }

    private static int scaleCount(int value, int cap, int each) {
        return Math.clamp(value, 0, cap) * each;
    }

    /** 张力底分取 z、新极值、排名变化里最强的一个；没有信号就是 0，等模型上浮。 */
    // shortcut: 机会的 signals 目前没有 rank_delta 生产者，这一段只有在将来写入排名变化后才生效；
    // 热榜候选只有 bestRank/heatScore，没有绝对位次差，同样取不到张力，阶段 1 先如实留 0。
    private static int tension(OpportunityFacts facts) {
        double z = facts.zscore() == null || !Double.isFinite(facts.zscore())
            ? 0 : Math.min(Math.abs(facts.zscore()), 6) / 6;
        double extreme = Boolean.TRUE.equals(facts.extreme()) ? 1 : 0;
        double rank = facts.rankDelta() == null ? 0 : Math.min(Math.abs(facts.rankDelta()), 10) / 10.0;
        return (int) Math.round(Math.max(z, Math.max(extreme, rank)) * 100);
    }

    /** 视觉底分只看当前池里的数据源形态包不包含这条 shape。 */
    private static int visual(OpportunityFacts facts) {
        if (facts.shape() == null || facts.shape().isBlank()) {
            return 0;
        }
        return facts.supportedShapes().contains(facts.shape().strip().toLowerCase(Locale.ROOT)) ? 100 : 0;
    }

    private static int novelty(OpportunityFacts facts, List<OpportunityFacts.Publication> publications,
                               LocalDateTime now) {
        double maximum = 0;
        if (publications != null) {
            for (OpportunityFacts.Publication publication : publications) {
                if (publication != null && OpportunityGate.isRecent(publication.publishedAt(), now)) {
                    maximum = Math.max(maximum,
                        OpportunityGate.bigramJaccard(facts.normalizedTitle(), publication.title()));
                }
            }
        }
        return (int) Math.round((1 - maximum) * 100);
    }

    private static int timeliness(OpportunityFacts facts, LocalDateTime now) {
        if (facts.observedAt() == null || "knowledge".equals(facts.sourceType())
            || ("opportunity".equals(facts.sourceType()) && !newsCadence(facts.cadence()))) {
            return 50;
        }
        double hours = Math.max(0, Duration.between(facts.observedAt(), now).toMillis() / 3_600_000.0);
        return (int) Math.round(Math.exp(-hours / 24) * 100);
    }

    private static boolean newsCadence(String cadence) {
        return cadence != null && switch (cadence.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "realtime", "daily", "weekly" -> true;
            default -> false;
        };
    }

    private static double weighted(OpportunityScore.Dimensions dimensions, OpportunityScoringConfig config) {
        double sum = (double) dimensions.evidence() * config.evidenceWeight()
            + (double) dimensions.novelty() * config.noveltyWeight()
            + (double) dimensions.timeliness() * config.timelinessWeight()
            + (double) dimensions.audience() * config.audienceWeight()
            + (double) dimensions.tension() * config.tensionWeight()
            + (double) dimensions.visual() * config.visualWeight();
        return sum / weightSum(config);
    }

    private static int weightSum(OpportunityScoringConfig config) {
        return config.evidenceWeight() + config.noveltyWeight() + config.timelinessWeight()
            + config.audienceWeight() + config.tensionWeight() + config.visualWeight();
    }

    /**
     * 评分权重与时间锚点。
     *
     * <p>权重全部非负且总和必须大于 0，时间锚点不能为空，否则构造抛 {@link IllegalArgumentException}。
     *
     * @param evidenceWeight   证据维权重
     * @param noveltyWeight    新颖度维权重
     * @param timelinessWeight 时效维权重
     * @param audienceWeight   受众维权重（当前受众分固定 50，等模型接入）
     * @param tensionWeight    张力维权重
     * @param visualWeight     视觉维权重
     * @param now              新颖度与时效性的时间锚点
     */
    public record OpportunityScoringConfig(
        int evidenceWeight,
        int noveltyWeight,
        int timelinessWeight,
        int audienceWeight,
        int tensionWeight,
        int visualWeight,
        LocalDateTime now
    ) {

        public OpportunityScoringConfig {
            if (evidenceWeight < 0 || noveltyWeight < 0 || timelinessWeight < 0
                || audienceWeight < 0 || tensionWeight < 0 || visualWeight < 0) {
                throw new IllegalArgumentException("评分权重不能为负数");
            }
            long sum = (long) evidenceWeight + noveltyWeight + timelinessWeight
                + audienceWeight + tensionWeight + visualWeight;
            if (sum == 0) {
                throw new IllegalArgumentException("评分权重总和不能为 0");
            }
            if (now == null) {
                throw new IllegalArgumentException("评分时间不能为空");
            }
        }

        /** 默认权重：证据 20、新颖 15、时效 10、受众 25、张力 20、视觉 10。 */
        public static OpportunityScoringConfig defaults(LocalDateTime now) {
            return new OpportunityScoringConfig(20, 15, 10, 25, 20, 10, now);
        }
    }
}
