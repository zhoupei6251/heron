package org.dromara.heron.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 池摘要要能证明「两阶段看到的是同一份内容」。
 * 因此：内容变必须变，审计字段变必须不变——否则一次认领写库就让校验变成噪音。
 */
@DisplayName("候选池摘要：内容敏感、审计无关")
class PoolDigestTest {

    private static HeronNewsCandidate candidate(long id, String title) {
        HeronNewsCandidate item = new HeronNewsCandidate();
        item.setCandidateId(id);
        item.setTitle(title);
        item.setSummary("摘要");
        item.setHeatScore(9L);
        item.setBestRank(1);
        item.setLastSeenAt(LocalDateTime.of(2026, 11, 12, 8, 0));
        return item;
    }

    private static String digest(HeronNewsCandidate... hot) {
        return PoolDigest.of(List.of(hot), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("同一内容两次摘要相同")
    void stable() {
        assertEquals(digest(candidate(1L, "甲")), digest(candidate(1L, "甲")));
    }

    @Test
    @DisplayName("改标题 / 改摘要 / 改热度都要变")
    void contentSensitive() {
        String base = digest(candidate(1L, "甲"));

        HeronNewsCandidate renamed = candidate(1L, "甲改");
        HeronNewsCandidate resumed = candidate(1L, "甲");
        resumed.setSummary("另一个摘要");
        HeronNewsCandidate hotter = candidate(1L, "甲");
        hotter.setHeatScore(99L);

        assertNotEquals(base, digest(renamed));
        assertNotEquals(base, digest(resumed));
        assertNotEquals(base, digest(hotter));
    }

    @Test
    @DisplayName("增删候选要变")
    void membershipSensitive() {
        String one = digest(candidate(1L, "甲"));
        String two = digest(candidate(1L, "甲"), candidate(2L, "乙"));
        assertNotEquals(one, two);
    }

    @Test
    @DisplayName("只改审计字段不变：认领/更新时间不是内容")
    void auditFieldsIgnored() {
        HeronNewsCandidate plain = candidate(1L, "甲");
        HeronNewsCandidate touched = candidate(1L, "甲");
        touched.setUpdateTime(LocalDateTime.of(2026, 11, 12, 9, 30));
        touched.setUpdateBy(42L);
        touched.setClaimedBy(7L);
        touched.setClaimedAt(LocalDateTime.of(2026, 11, 12, 9, 31));

        assertEquals(digest(plain), digest(touched));
    }

    @Test
    @DisplayName("证据只算引用集合：顺序不同不影响")
    void evidenceIsASet() {
        HeronNewsCandidate first = candidate(1L, "甲");
        first.setEvidence("{\"items\":[{\"sourceItemId\":\"a\",\"sourceUrl\":\"https://a\"},"
            + "{\"sourceItemId\":\"b\",\"sourceUrl\":\"https://b\"}]}");
        HeronNewsCandidate second = candidate(1L, "甲");
        second.setEvidence("{\"items\":[{\"sourceItemId\":\"b\",\"sourceUrl\":\"https://b\"},"
            + "{\"sourceItemId\":\"a\",\"sourceUrl\":\"https://a\"}]}");

        assertEquals(digest(first), digest(second));
    }

    @Test
    @DisplayName("数据源候选变了摘要就变")
    void datasetsParticipate() {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey("worldbank-gdp");
        source.setName("世界银行 GDP");
        source.setCadence("annual");
        source.setShapes("long");

        String withSource = PoolDigest.of(List.of(candidate(1L, "甲")), List.of(), List.of(source),
            List.of(), List.of());
        String withoutSource = digest(candidate(1L, "甲"));

        assertNotEquals(withSource, withoutSource);
    }
}
