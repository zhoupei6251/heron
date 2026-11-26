package org.dromara.heron.service.impl;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 选题前硬门禁：先重复、再样本、最后合规；顺序就是可诊断的拒绝优先级。 */
public final class OpportunityGate {

    private static final OpportunityScore.GateResult PASS =
        new OpportunityScore.GateResult(OpportunityScore.Gate.PASS, null);

    private OpportunityGate() {
    }

    public static OpportunityScore.GateResult evaluate(OpportunityFacts facts,
                                                       List<OpportunityFacts.Publication> recentPublications,
                                                       List<String> blocklist) {
        return evaluate(facts, recentPublications, blocklist, facts.observedAt());
    }

    /**
     * @param reference 「近 14 天」的锚点。生产传当前时间：候选可能几天前就进池，
     *                  拿它自己的观察时间当锚点会漏掉这期间已发布的作品。
     */
    public static OpportunityScore.GateResult evaluate(OpportunityFacts facts,
                                                       List<OpportunityFacts.Publication> recentPublications,
                                                       List<String> blocklist,
                                                       LocalDateTime reference) {
        if (recentPublications != null) {
            for (OpportunityFacts.Publication publication : recentPublications) {
                if (publication != null && isRecent(publication.publishedAt(), reference)
                    && (sameKey(facts.dedupKey(), publication.dedupKey())
                        || bigramJaccard(facts.normalizedTitle(), publication.title()) >= 0.8)) {
                    return block("duplicate_recent: version " + publication.versionId()
                        + "《" + publication.title() + "》");
                }
            }
        }
        if ("opportunity".equals(facts.sourceType())) {
            // points 是实到点数（rows 形态下就是行数），windowPoints 是请求点数；两者一起写进原因才看得出差在哪。
            double coverage = facts.coverage() == null ? 0 : facts.coverage();
            int points = facts.points() == null ? 0 : facts.points();
            int windowPoints = facts.windowPoints() == null ? 0 : facts.windowPoints();
            if (coverage < 0.6 || points < 8) {
                return block("sample_insufficient: coverage=" + coverage
                    + ", points=" + points + "/" + windowPoints);
            }
        }
        if (blocklist != null) {
            String text = (text(facts.label()) + "\n" + text(facts.title())).toLowerCase(Locale.ROOT);
            for (String keyword : blocklist) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                String trimmed = keyword.strip();
                if (text.contains(trimmed.toLowerCase(Locale.ROOT))) {
                    return block("compliance_risk: keyword=" + trimmed);
                }
            }
        }
        return PASS;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    static boolean sameKey(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    /** 标题先归一再按 Unicode code point 取 bigram，避免中文被 UTF-16 截断。 */
    // shortcut: bigram 漏掉同义改写，只拦近似重复；要上向量去重时再换。
    static double bigramJaccard(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        int union = a.size() + b.size() - intersection.size();
        return union == 0 ? 0 : (double) intersection.size() / union;
    }

    private static Set<String> bigrams(String title) {
        if (title == null || title.isBlank()) {
            return Set.of();
        }
        int[] points = NewsDedup.normalizedTitle(title).codePoints().toArray();
        Set<String> result = new HashSet<>();
        for (int i = 0; i < points.length - 1; i++) {
            result.add(new String(points, i, 2));
        }
        return result;
    }

    static boolean isRecent(LocalDateTime publishedAt, LocalDateTime reference) {
        if (publishedAt == null) {
            return false;
        }
        return reference == null || (!publishedAt.isAfter(reference)
            && !publishedAt.isBefore(reference.minusDays(14)));
    }    private static OpportunityScore.GateResult block(String reason) {
        return new OpportunityScore.GateResult(OpportunityScore.Gate.BLOCK, reason);
    }
}
