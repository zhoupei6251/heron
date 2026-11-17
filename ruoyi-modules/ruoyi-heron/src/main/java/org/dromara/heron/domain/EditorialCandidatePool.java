package org.dromara.heron.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 自动线的候选池：一次性建好，总编辑与线内编辑都从这里取。
 *
 * <p>五类候选分开装：热榜标题、带正文文章、可用数据源、自动来源、最近已发布。
 * 之所以要 {@code poolId} 与 {@code poolHash} 两个身份：
 * id 标识「这一次快照」，hash 证明「内容没被换过」——只有 id 相同不足以说明内容一致。
 *
 * <p>建好即不可变；分线判定（有正文才走解说线）由 {@link #of} 在代码里完成，不交给模型。
 */
public record EditorialCandidatePool(String poolId, String poolHash,
                                     List<HeronNewsCandidate> hot, List<HeronNewsCandidate> articles,
                                     List<HeronDataSource> datasets, List<HeronNewsSource> sources,
                                     List<HeronNewsWork> recent, List<HeronDataOpportunity> opportunities) {

    public EditorialCandidatePool {
        if (poolId == null || poolId.isBlank()) {
            throw new IllegalStateException("候选池缺少 poolId");
        }
        if (poolHash == null || !poolHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("候选池缺少合法 poolHash：" + poolHash);
        }
        hot = hot == null ? List.of() : List.copyOf(hot);
        articles = articles == null ? List.of() : List.copyOf(articles);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        sources = sources == null ? List.of() : List.copyOf(sources);
        recent = recent == null ? List.of() : List.copyOf(recent);
        opportunities = opportunities == null ? List.of() : List.copyOf(opportunities);
    }

    public static EditorialCandidatePool empty() {
        return build(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 老入口：没有数据机会时的池子（机会是后加的一条线）。 */
    public static EditorialCandidatePool of(List<HeronNewsCandidate> candidates,
                                            Predicate<HeronNewsCandidate> hasBody,
                                            List<HeronDataSource> datasets,
                                            List<HeronNewsSource> sources,
                                            List<HeronNewsWork> recent) {
        return of(candidates, hasBody, datasets, sources, recent, List.of());
    }

    /**
     * 建池：先按「有没有正文」分线，再连同其余候选一起算摘要。
     * 标题为空的候选直接丢掉，不占名额。
     */
    public static EditorialCandidatePool of(List<HeronNewsCandidate> candidates,
                                            Predicate<HeronNewsCandidate> hasBody,
                                            List<HeronDataSource> datasets,
                                            List<HeronNewsSource> sources,
                                            List<HeronNewsWork> recent,
                                            List<HeronDataOpportunity> opportunities) {
        List<HeronNewsCandidate> hot = new ArrayList<>();
        List<HeronNewsCandidate> articles = new ArrayList<>();
        for (HeronNewsCandidate candidate : candidates == null ? List.<HeronNewsCandidate>of() : candidates) {
            String title = candidate == null ? null : candidate.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            if (hasBody.test(candidate)) {
                articles.add(candidate);
            } else {
                hot.add(candidate);
            }
        }
        return build(hot, articles, datasets, sources, recent, opportunities);
    }

    /** 三条线都空才算空池：没有新闻但数据里有值得做的东西时，数据看板照样能产。 */
    public boolean isEmpty() {
        return hot.isEmpty() && articles.isEmpty() && opportunities.isEmpty();
    }

    /**
     * 重试时剔除已经试过、审稿没过的选题。
     *
     * <p>池子内容变了，hash 必须重算——hash 的语义是「内容没被换过」，
     * 留着旧 hash 等于声称这一池和上一池是同一批东西。
     *
     * <p>机会按它绑定的源剔除：同一条数据源换个机会再选出来，审稿一样过不了。
     *
     * @param candidateIds 解说线已试过的候选
     * @param sourceKeys   数据看板线已试过的数据源
     */
    public EditorialCandidatePool excluding(Set<Long> candidateIds, Set<String> sourceKeys) {
        if (candidateIds.isEmpty() && sourceKeys.isEmpty()) {
            return this;
        }
        return build(
            hot.stream().filter(item -> !candidateIds.contains(item.getCandidateId())).toList(),
            articles.stream().filter(item -> !candidateIds.contains(item.getCandidateId())).toList(),
            datasets.stream().filter(item -> !sourceKeys.contains(item.getSourceKey())).toList(),
            sources, recent,
            opportunities.stream().filter(item -> !sourceKeys.contains(item.getSourceKey())).toList());
    }

    private static EditorialCandidatePool build(List<HeronNewsCandidate> hot, List<HeronNewsCandidate> articles,
                                                List<HeronDataSource> datasets, List<HeronNewsSource> sources,
                                                List<HeronNewsWork> recent, List<HeronDataOpportunity> opportunities) {
        return new EditorialCandidatePool(UUID.randomUUID().toString(),
            PoolDigest.of(hot, articles, datasets, sources, recent, opportunities),
            hot, articles, datasets, sources, recent, opportunities);
    }
}
