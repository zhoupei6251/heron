package org.dromara.heron.agentscope;

import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 只读工具面的边界：取值有上限、缺数据不编造、查不到就说查不到。
 * 这些工具的输出会直接进模型上下文，不设上限就是给预算开后门。
 */
@DisplayName("研究工具：有界返回与缺数据行为")
class HeronResearchToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static HeronResearchTools tools(DatasetFetchService datasets, HeronDataOpportunityMapper mapper) {
        return new HeronResearchTools(datasets, mapper);
    }

    private static JsonNode snapshot(int pointCount) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < pointCount; i++) {
            if (i > 0) {
                points.append(',');
            }
            points.append("{\"date\":\"2026-11-").append(i + 1).append("\",\"value\":").append(i).append('}');
        }
        // 数据点在 rows 下，metric 是个对象——与真实 dataset/1 契约一致
        return JSON.readTree("{\"contract\":\"dataset/1\",\"shape\":\"wide\",\"cadence\":\"daily\","
            + "\"retrieved_at\":\"2026-11-13T00:00:00Z\",\"metric\":{\"key\":\"aud_cny\",\"unit\":\"CNY\"},"
            + "\"columns\":[{\"key\":\"value\"}],"
            + "\"rows\":[" + points + "]}");
    }

    @Test
    @DisplayName("点数被夹到上限，不会把整条序列塞进上下文")
    void capsPointsToUpperBound() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(snapshot(3));

        tools(datasets, mock(HeronDataOpportunityMapper.class)).queryTrendSeries("frankfurter-audcny", 9999);

        verify(datasets).snapshot("frankfurter-audcny", HeronResearchTools.MAX_POINTS);
    }

    @Test
    @DisplayName("不传点数走默认值，传 1 也不低于下限")
    void appliesDefaultAndLowerBound() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(snapshot(3));
        HeronResearchTools subject = tools(datasets, mock(HeronDataOpportunityMapper.class));

        subject.queryTrendSeries("k", 0);
        verify(datasets).snapshot("k", HeronResearchTools.DEFAULT_POINTS);

        subject.queryTrendSeries("k", 1);
        verify(datasets).snapshot("k", 2);
    }

    @Test
    @DisplayName("序列返回形状与末尾点，且不含 Jackson 3 节点")
    void returnsPlainValuesOnly() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(snapshot(3));

        Map<String, Object> result = tools(datasets, mock(HeronDataOpportunityMapper.class))
            .queryTrendSeries("k", 2);

        assertEquals("wide", result.get("shape"));
        assertEquals("daily", result.get("cadence"));
        assertEquals(2, result.get("requestedPoints"));
        assertEquals(3, result.get("returnedPoints"), "源里实际有几个点要单独给出，模型才能判断历史够不够");
        List<?> points = (List<?>) result.get("points");
        assertEquals(2, points.size(), "只取末尾两个点");
        assertTrue(points.stream().allMatch(String.class::isInstance), "必须是文本，不能是 JsonNode");
        result.values().stream()
            .filter(value -> !(value instanceof String) && !(value instanceof Integer))
            .forEach(value -> assertFalse(value instanceof JsonNode, "不能把 JsonNode 交给 Jackson 2 序列化"));
    }

    @Test
    @DisplayName("机会不存在时明确说找不到，不返回半成品")
    void reportsMissingOpportunity() {
        HeronDataOpportunityMapper mapper = mock(HeronDataOpportunityMapper.class);
        when(mapper.selectById(42L)).thenReturn(null);

        Map<String, Object> result = tools(mock(DatasetFetchService.class), mapper).queryOpportunityDetail(42L);

        assertEquals(false, result.get("found"));
        assertEquals(42L, result.get("opportunityId"));
    }

    @Test
    @DisplayName("机会存在时带上判断所需的字段，信号过长被截断")
    void returnsOpportunityFields() {
        HeronDataOpportunity row = new HeronDataOpportunity();
        row.setOpportunityId(7L);
        row.setSourceKey("frankfurter-audcny");
        row.setEntity("AUD/CNY");
        row.setMetric("rate");
        row.setShape("wide");
        row.setCadence("daily");
        row.setWindowPoints(7);
        row.setPoints(7);
        row.setPeriodStart("2026-11-01");
        row.setPeriodEnd("2026-11-07");
        row.setScore(BigDecimal.valueOf(69.94));
        row.setStatus("new");
        row.setSignals("x".repeat(HeronResearchTools.MAX_RESULT_CHARS + 100));

        HeronDataOpportunityMapper mapper = mock(HeronDataOpportunityMapper.class);
        when(mapper.selectById(7L)).thenReturn(row);

        Map<String, Object> result = tools(mock(DatasetFetchService.class), mapper).queryOpportunityDetail(7L);

        assertEquals(true, result.get("found"));
        assertEquals("frankfurter-audcny", result.get("sourceKey"));
        assertEquals("69.94", result.get("score"));
        assertEquals(7, result.get("windowPoints"));
        String signals = (String) result.get("signals");
        assertTrue(signals.length() <= HeronResearchTools.MAX_RESULT_CHARS + 1, "信号要被截断：" + signals.length());
        assertEquals("missing", result.get("snapshot"), "没有落快照时要明说，不能让模型以为查过了");
    }

    @Test
    @DisplayName("机会带快照时，详情里能拿到当时看到的数据")
    void returnsStoredSnapshot() {
        HeronDataOpportunity row = new HeronDataOpportunity();
        row.setOpportunityId(8L);
        row.setStatus("new");
        row.setSnapshotJson("""
            {"contract":"dataset/1","shape":"wide","cadence":"daily","retrieved_at":"2026-11-13T00:00:00Z",
             "data_sha256":"abc123","source_points":2,"truncated":false,
             "points":[{"date":"2026-11-12","value":4.6},{"date":"2026-11-13","value":4.58}]}
            """);
        HeronDataOpportunityMapper mapper = mock(HeronDataOpportunityMapper.class);
        when(mapper.selectById(8L)).thenReturn(row);

        Map<String, Object> result = tools(mock(DatasetFetchService.class), mapper).queryOpportunityDetail(8L);

        assertEquals("abc123", result.get("snapshotDataSha256"));
        assertEquals(2, result.get("snapshotSourcePoints"));
        assertEquals(false, result.get("snapshotTruncated"));
        assertEquals(2, ((List<?>) result.get("snapshotPoints")).size());
    }
}
