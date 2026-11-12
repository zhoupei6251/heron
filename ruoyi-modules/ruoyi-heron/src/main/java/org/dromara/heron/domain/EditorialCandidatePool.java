package org.dromara.heron.domain;

import java.util.ArrayList;
import java.util.List;
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
                                     List<HeronNewsWork> recent) {

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
    }

    public static EditorialCandidatePool empty() {
        return build(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 建池：先按「有没有正文」分线，再连同其余三类候选一起算摘要。
     * 标题为空的候选直接丢掉，不占名额。
     */
    public static EditorialCandidatePool of(List<HeronNewsCandidate> candidates,
                                            Predicate<HeronNewsCandidate> hasBody,
                                            List<HeronDataSource> datasets,
                                            List<HeronNewsSource> sources,
                                            List<HeronNewsWork> recent) {
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
        return build(hot, articles, datasets, sources, recent);
    }

    /** 新闻候选为空才算空池：只有数据源没有稿子时，两条线都做不了。 */
    public boolean isEmpty() {
        return hot.isEmpty() && articles.isEmpty();
    }

    private static EditorialCandidatePool build(List<HeronNewsCandidate> hot, List<HeronNewsCandidate> articles,
                                                List<HeronDataSource> datasets, List<HeronNewsSource> sources,
                                                List<HeronNewsWork> recent) {
        return new EditorialCandidatePool(UUID.randomUUID().toString(),
            PoolDigest.of(hot, articles, datasets, sources, recent),
            hot, articles, datasets, sources, recent);
    }
}
