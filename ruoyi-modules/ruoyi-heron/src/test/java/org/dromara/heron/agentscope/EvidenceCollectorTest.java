package org.dromara.heron.agentscope;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.heron.domain.HeronAgentToolCall;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 证据链的关键不是「记了哪些调用」，而是**能不能查出模型引用了不存在的东西**。
 * 模型可以在 reason 里写任何数字，账本是唯一能对质的地方。
 */
@DisplayName("证据链：模型声明与账本核对")
class EvidenceCollectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "toolCall"), HeronAgentToolCall.class);
    }

    private static HeronAgentToolCall call(long id, int round, String tool, String request, String response) {
        HeronAgentToolCall row = new HeronAgentToolCall();
        row.setCallId(id);
        row.setResearchId(9L);
        row.setAttemptNo(1);
        row.setRoundNo(round);
        row.setToolName(tool);
        row.setStatus(HeronAgentToolCall.STATUS_OK);
        row.setRequestSummary(request);
        row.setResponseSummary(response);
        return row;
    }

    private static HeronAgentToolCallMapper mapperWith(List<HeronAgentToolCall> rows) {
        HeronAgentToolCallMapper mapper = mock(HeronAgentToolCallMapper.class);
        var chain = mock(org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper.class);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.orderByAsc(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class))).thenReturn(chain);
        when(chain.last(any())).thenReturn(chain);
        when(chain.list()).thenReturn(rows);
        when(mapper.lambda()).thenReturn(chain);
        return mapper;
    }

    @Test
    @DisplayName("模型声明过的源在账本里出现：算已核实")
    void verifiesCitedSource() {
        var rows = List.of(call(1L, 1, "query_trend_series",
            "{\"sourceKey\":\"frankfurter-audcny\"}", "returnedPoints=8"));
        String json = new EvidenceCollector(mapperWith(rows))
            .collect(9L, 1, List.of("frankfurter-audcny"), true);

        JsonNode root = JSON.readTree(json);
        assertEquals(1, root.path("toolCallCount").asInt());
        assertEquals(0, root.path("unverifiedCitations").size());
        assertTrue(root.path("complete").asBoolean());
        assertEquals("frankfurter-audcny", root.path("calls").get(0).path("cited").asString());
    }

    /** 这是整个核对器的存在理由：模型说了它没查过的东西。 */
    @Test
    @DisplayName("模型引用了账本里没有的源：列进未核实，且不算完整")
    void flagsUnverifiedCitation() {
        var rows = List.of(call(1L, 1, "query_trend_series",
            "{\"sourceKey\":\"frankfurter-audcny\"}", "ok"));
        String json = new EvidenceCollector(mapperWith(rows))
            .collect(9L, 1, List.of("frankfurter-audcny", "bloomberg-gold"), true);

        JsonNode root = JSON.readTree(json);
        assertEquals(1, root.path("unverifiedCitations").size());
        assertEquals("bloomberg-gold", root.path("unverifiedCitations").get(0).asString());
        assertFalse(root.path("complete").asBoolean(), "有查不到的引用就不能说完备");
    }

    @Test
    @DisplayName("模型没交结论：证据只算过程记录，不能算支撑判断的证据")
    void notCompleteWithoutSubmission() {
        var rows = List.of(call(1L, 1, "query_opportunity_detail", "{}", "ok"));
        String json = new EvidenceCollector(mapperWith(rows)).collect(9L, 1, List.of(), false);

        JsonNode root = JSON.readTree(json);
        assertFalse(root.path("complete").asBoolean());
        assertEquals(0, root.path("unverifiedCitations").size(), "没声明过就不存在未核实引用");
    }

    @Test
    @DisplayName("一次都没查就交结论：证据链仍然完整但调用数为零")
    void emptyLedger() {
        String json = new EvidenceCollector(mapperWith(List.of())).collect(9L, 1, List.of(), true);

        JsonNode root = JSON.readTree(json);
        assertEquals(0, root.path("toolCallCount").asInt());
        assertTrue(root.path("complete").asBoolean());
    }

    @Test
    @DisplayName("账本读不出来时返回 null，不编一个看起来完整的空壳")
    void returnsNullWhenLedgerUnavailable() {
        HeronAgentToolCallMapper mapper = mock(HeronAgentToolCallMapper.class);
        when(mapper.lambda()).thenThrow(new IllegalStateException("db down"));

        assertNull(new EvidenceCollector(mapper).collect(9L, 1, List.of("x"), true));
    }

    @Test
    @DisplayName("出参摘要过长被截断：证据是索引，不是本体")
    void truncatesLongResponds() {
        var rows = List.of(call(1L, 1, "query_trend_series", "{}", "y".repeat(5000)));
        String json = new EvidenceCollector(mapperWith(rows)).collect(9L, 1, List.of(), true);

        String response = JSON.readTree(json).path("calls").get(0).path("response").asString();
        assertTrue(response.length() <= 401, "摘要长度 " + response.length());
        assertTrue(response.endsWith("…"));
    }

    /**
     * 只证明「某个 key 在账本里出现过」并不够：结论里的数字到底来自哪一版数据，
     * 要能指到本次 attempt 冻结的那份快照。
     */
    @Test
    @DisplayName("证据条目绑到本次 attempt 冻结的快照版本")
    void bindsSnapshotVersion() {
        var rows = List.of(call(1L, 1, "query_trend_series",
            "{\"sourceKey\":\"frankfurter-audcny\"}", "returnedPoints=8"));
        var snapshots = java.util.Map.of("frankfurter-audcny",
            new ResearchSnapshots.Ref("frankfurter-audcny", "sha-1", "2026-11-16T10:00:00Z"));

        String json = new EvidenceCollector(mapperWith(rows)).collect(9L, 1, List.of(), true, snapshots);

        JsonNode root = JSON.readTree(json);
        JsonNode item = root.path("calls").get(0);
        assertEquals("frankfurter-audcny", item.path("snapshotSourceKey").asString(""));
        assertEquals("sha-1", item.path("snapshotSha256").asString(""), "没有版本号的证据等于没有证据");
        assertEquals(1, root.path("snapshotCount").asInt());
        assertEquals("sha-1", root.path("snapshots").get(0).path("dataSha256").asString(""));
    }
}
