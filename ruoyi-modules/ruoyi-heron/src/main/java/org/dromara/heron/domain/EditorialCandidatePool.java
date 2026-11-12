package org.dromara.heron.domain;

import java.util.List;

/**
 * 自动线的候选池：结构化对象，不是标题字符串。
 *
 * <p>总编辑看的是摘要，线内编辑从这个池子里挑具体条目——两处必须是同一批数据。
 * 之前线内编辑收到摘要后自己又查了一次库，于是「总编辑看到的」和「真正选的」
 * 是两个集合，池子的边界（来源白名单、24 小时时效）在那一次重查里全丢了。
 *
 * <p>两类分开是因为它们的素材能力不同：有正文的才能走解说线，
 * 只有标题的只能靠数据看板。这个判定由 {@link #sort} 在代码里完成，不交给模型。
 */
public record EditorialCandidatePool(List<HeronNewsCandidate> hot,
                                     List<HeronNewsCandidate> articles) {

    public EditorialCandidatePool {
        hot = hot == null ? List.of() : List.copyOf(hot);
        articles = articles == null ? List.of() : List.copyOf(articles);
    }

    public static EditorialCandidatePool empty() {
        return new EditorialCandidatePool(List.of(), List.of());
    }

    public boolean isEmpty() {
        return hot.isEmpty() && articles.isEmpty();
    }

    /** 有正文的走解说线，其余进热榜池。判定依据是证据里有没有原文链接。 */
    public static EditorialCandidatePool sort(List<HeronNewsCandidate> candidates,
                                              java.util.function.Predicate<HeronNewsCandidate> hasBody) {
        List<HeronNewsCandidate> hot = new java.util.ArrayList<>();
        List<HeronNewsCandidate> articles = new java.util.ArrayList<>();
        for (HeronNewsCandidate candidate : candidates) {
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
        return new EditorialCandidatePool(hot, articles);
    }
}
