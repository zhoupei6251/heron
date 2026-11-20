package org.dromara.heron.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 候选池是总编辑和线内编辑共用的唯一来源。
 * 之前线内编辑收到摘要后又自己查了一次库，来源白名单与 24 小时时效在那次重查里丢掉了。
 */
@DisplayName("候选池：分线与边界")
class EditorialCandidatePoolTest {

    private static HeronNewsCandidate candidate(String title) {
        HeronNewsCandidate item = new HeronNewsCandidate();
        item.setCandidateId((long) title.hashCode());
        item.setTitle(title);
        return item;
    }

    private static EditorialCandidatePool pool(List<HeronNewsCandidate> items,
                                               java.util.function.Predicate<HeronNewsCandidate> hasBody) {
        return EditorialCandidatePool.of(items, hasBody, List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("有正文的进解说池，其余进热榜池")
    void splitsByBody() {
        HeronNewsCandidate withBody = candidate("有正文");
        HeronNewsCandidate titleOnly = candidate("只有标题");

        EditorialCandidatePool pool = pool(List.of(withBody, titleOnly), item -> item == withBody);

        assertEquals(List.of(withBody), pool.articles());
        assertEquals(List.of(titleOnly), pool.hot());
        assertFalse(pool.isEmpty());
    }

    @Test
    @DisplayName("标题为空的候选被丢掉，不占名额")
    void dropsBlankTitles() {
        HeronNewsCandidate blank = candidate("   ");
        EditorialCandidatePool pool = pool(List.of(blank), item -> false);
        assertTrue(pool.isEmpty());
    }

    @Test
    @DisplayName("空池可判定，调用方据此跳过而不是硬做")
    void emptyIsDetectable() {
        assertTrue(EditorialCandidatePool.empty().isEmpty());
        assertTrue(pool(List.of(), item -> false).isEmpty());
    }

    @Test
    @DisplayName("池是不可变的，建好之后不会被下游改掉")
    void isImmutable() {
        EditorialCandidatePool pool = pool(List.of(candidate("甲")), item -> false);
        assertThrows(UnsupportedOperationException.class, () -> pool.hot().add(candidate("乙")));
    }

    @Test
    @DisplayName("建池即带内容摘要：64 位小写十六进制，空池也有")
    void carriesPoolDigest() {
        assertTrue(EditorialCandidatePool.empty().poolHash().matches("[0-9a-f]{64}"));
        assertTrue(pool(List.of(candidate("甲")), item -> false).poolHash().matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("内容变了摘要就变，池 ID 每池独立")
    void digestTracksContentAndIdIsUnique() {
        HeronNewsCandidate first = candidate("甲");
        HeronNewsCandidate second = candidate("乙");

        EditorialCandidatePool a = pool(List.of(first), item -> false);
        EditorialCandidatePool b = pool(List.of(second), item -> false);

        assertNotEquals(a.poolHash(), b.poolHash());
        assertNotEquals(a.poolId(), b.poolId());
        assertFalse(a.poolId().isBlank());
    }

    /**
     * 审稿没过换选题时剔除已试过的。同一条源换个机会再选出来，审稿一样过不了——
     * 所以机会按它绑定的源一起剔。
     */
    @Test
    @DisplayName("剔除已试过的候选与数据源，hash 跟着重算")
    void excludingDropsTriedItemsAndRehashes() {
        HeronNewsCandidate tried = candidate("试过的");
        HeronNewsCandidate fresh = candidate("没试过的");
        HeronDataSource triedSource = new HeronDataSource();
        triedSource.setSourceKey("tried-source");
        HeronDataSource freshSource = new HeronDataSource();
        freshSource.setSourceKey("fresh-source");

        EditorialCandidatePool before = EditorialCandidatePool.of(
            List.of(tried, fresh), item -> true, List.of(triedSource, freshSource), List.of(), List.of());

        EditorialCandidatePool after = before.excluding(
            java.util.Set.of(tried.getCandidateId()), java.util.Set.of("tried-source"));

        assertEquals(List.of(fresh), after.articles());
        assertTrue(after.datasets().stream().noneMatch(item -> "tried-source".equals(item.getSourceKey())));
        assertEquals(1, after.datasets().size());
        assertNotEquals(before.poolHash(), after.poolHash(),
            "内容变了 hash 必须重算，留着旧的等于声称还是同一批东西");
    }

    @Test
    @DisplayName("知识来源单独成池，不混进解说线")
    void separatesKnowledgeCandidates() {
        HeronNewsCandidate knowledge = candidate("知识主题");
        knowledge.setSourceKind(HeronNewsCandidate.SOURCE_KNOWLEDGE);
        knowledge.setEvidence("{\"items\":[{\"sourceItemId\":\"05-01\",\"sourceUrl\":\"https://example.com\"}]}");

        EditorialCandidatePool pool = pool(List.of(knowledge), item -> true);

        assertEquals(List.of(knowledge), pool.knowledge());
        assertEquals(List.of(), pool.articles());
        assertEquals(List.of(), pool.hot());
        assertFalse(pool.isEmpty());
    }

    @Test
    @DisplayName("没试过任何选题时返回自身，不白白重算")
    void excludingNothingReturnsSamePool() {
        EditorialCandidatePool pool = pool(List.of(candidate("a")), item -> true);

        assertSame(pool, pool.excluding(java.util.Set.of(), java.util.Set.of()));
    }
}
