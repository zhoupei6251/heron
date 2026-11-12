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
}
