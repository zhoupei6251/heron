package org.dromara.heron.agentscope;

import org.dromara.heron.domain.HeronResearchSnapshot;
import org.dromara.heron.mapper.HeronResearchSnapshotMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 快照冻结的两条边界：同源只留第一份、落库失败不打断研究。
 *
 * <p>这两条都是「错了也不会当场报错」的类型：前者会让一次 attempt 的同一源出现两个版本，
 * 后者会让整次研究因为一张证据表写不进去而失败。
 */
@DisplayName("研究快照：一次 attempt 冻结一份，失败不打断")
class ResearchSnapshotsTest {

    private static final String SNAPSHOT = """
        {"contract":"dataset/1","data_sha256":"sha-1","retrieved_at":"2026-11-16T10:00:00Z",
         "rows":[{"date":"2026-11-15","value":1},{"date":"2026-11-16","value":2}]}
        """;

    private static tools.jackson.databind.JsonNode snapshot(String sha) {
        return tools.jackson.databind.json.JsonMapper.builder().build()
            .readTree(SNAPSHOT.replace("sha-1", sha));
    }

    @Test
    @DisplayName("同一 attempt 同一源只落一次，留第一份")
    void recordsOncePerSource() {
        HeronResearchSnapshotMapper mapper = mock(HeronResearchSnapshotMapper.class);
        ResearchSnapshots snapshots = new ResearchSnapshots(mapper, 9L, 2);

        snapshots.record("frankfurter-audcny", snapshot("sha-1"));
        snapshots.record("frankfurter-audcny", snapshot("sha-2"));

        var captor = org.mockito.ArgumentCaptor.forClass(HeronResearchSnapshot.class);
        verify(mapper, times(1)).insert(captor.capture());
        HeronResearchSnapshot row = captor.getValue();
        assertEquals("sha-1", row.getDataSha256(), "同一源出现两个版本说明这轮结论可疑，留第一份");
        assertEquals(9L, row.getResearchId());
        assertEquals(2, row.getAttemptNo());
        assertEquals("dataset/1", row.getContract());
        assertEquals(2, row.getPoints(), "点数按实际取到的算，不是请求的");
        assertEquals("sha-1", snapshots.recorded().get("frankfurter-audcny").dataSha256());
    }

    @Test
    @DisplayName("落库失败不抛：拿不到证据链比拿不到数据轻")
    void toleratesInsertFailure() {
        HeronResearchSnapshotMapper mapper = mock(HeronResearchSnapshotMapper.class);
        when(mapper.insert(any(HeronResearchSnapshot.class))).thenThrow(new IllegalStateException("db down"));
        ResearchSnapshots snapshots = new ResearchSnapshots(mapper, 9L, 1);

        assertDoesNotThrow(() -> snapshots.record("k", snapshot("sha-1")));
        assertFalse(snapshots.recorded().containsKey("k"),
            "库里没有这份快照，就不能让证据链引用它——有引用没证据比没引用更糟");
    }

    @Test
    @DisplayName("没有接线时不落库，也不装成落过了")
    void disabledDoesNotTouchDatabase() {
        ResearchSnapshots snapshots = ResearchSnapshots.disabled();

        snapshots.record("k", snapshot("sha-1"));

        assertTrue(snapshots.recorded().containsKey("k"));
    }

    @Test
    @DisplayName("空源或空快照直接忽略")
    void ignoresEmptyInput() {
        ResearchSnapshots snapshots = new ResearchSnapshots(mock(HeronResearchSnapshotMapper.class), 9L, 1);

        snapshots.record(null, snapshot("sha-1"));
        snapshots.record("  ", snapshot("sha-1"));
        snapshots.record("k", null);

        assertTrue(snapshots.recorded().isEmpty());
    }
}
