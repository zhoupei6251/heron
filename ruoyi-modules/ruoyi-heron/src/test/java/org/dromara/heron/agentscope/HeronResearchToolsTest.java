package org.dromara.heron.agentscope;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return new HeronResearchTools(datasets, mapper,
            mock(HeronDataSourceMapper.class), mock(HeronVideoVersionMapper.class));
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
    @DisplayName("不传点数走默认值")
    void appliesDefaultPoints() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(snapshot(3));

        tools(datasets, mock(HeronDataOpportunityMapper.class)).queryTrendSeries("k", 0);

        verify(datasets).snapshot("k", HeronResearchTools.DEFAULT_POINTS);
    }

    /**
     * 夹紧与拒绝是两种语义：夹紧会让模型以为自己查到了想要的范围，拒绝才会让它换个问法。
     */
    @Test
    @DisplayName("越界点数直接拒绝，不悄悄夹紧")
    void rejectsOutOfRangePoints() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        HeronResearchTools subject = tools(datasets, mock(HeronDataOpportunityMapper.class));

        assertThrows(HeronBusinessException.class, () -> subject.queryTrendSeries("k", 1));
        assertThrows(HeronBusinessException.class, () -> subject.queryTrendSeries("k", 9999));
        verify(datasets, never()).snapshot(anyString(), anyInt());
    }

    @Test
    @DisplayName("空 sourceKey 直接拒绝，不拿空串去查源")
    void rejectsBlankSourceKey() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        HeronResearchTools subject = tools(datasets, mock(HeronDataOpportunityMapper.class));

        assertThrows(HeronBusinessException.class, () -> subject.queryTrendSeries("  ", 7));
        assertThrows(HeronBusinessException.class, () -> subject.queryDatasetSnapshot(null));
        verify(datasets, never()).snapshot(anyString(), anyInt());
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

    @Test
    @DisplayName("八个工具的 schema 都能派生出来，参数名对得上")
    void registersAllToolsWithSchema() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new HeronResearchTools(null, null, null, null));

        assertEquals(Set.of(
            "query_opportunities", "query_opportunity_detail", "query_trend_series",
            "query_dataset_snapshot", "compare_series", "query_data_sources",
            "query_source_health", "query_recent_productions"), toolkit.getToolNames());

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(8, schemas.size());
        for (ToolSchema schema : schemas) {
            assertNotNull(schema.getParameters(), schema.getName() + " 没有派生出入参 schema");
        }

        ToolSchema compare = schemas.stream()
            .filter(schema -> "compare_series".equals(schema.getName()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) compare.getParameters().get("properties");
        assertTrue(props.containsKey("sourceKeys"));
        assertTrue(props.containsKey("points"));
    }

    @Test
    @DisplayName("数据集快照给结构与首尾样本，够判断口径而不灌满上下文")
    void returnsSnapshotStructure() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(snapshot(30));

        Map<String, Object> result = tools(datasets, mock(HeronDataOpportunityMapper.class))
            .queryDatasetSnapshot("frankfurter-audcny");

        assertEquals("dataset/1", result.get("contract"));
        assertEquals("wide", result.get("shape"));
        assertEquals("aud_cny", result.get("metric"));
        assertEquals(30, result.get("totalRows"));
        assertEquals(2, ((List<?>) result.get("firstRows")).size());
        assertEquals(2, ((List<?>) result.get("lastRows")).size());
    }

    @Test
    @DisplayName("交叉比较一次最多三个源")
    void rejectsTooManyCompareKeys() {
        HeronResearchTools subject = tools(mock(DatasetFetchService.class), mock(HeronDataOpportunityMapper.class));

        assertThrows(HeronBusinessException.class, () -> subject.compareSeries("a,b,c,d", 7));
    }

    @Test
    @DisplayName("算不出信号的源如实说不支持，不编一个 0")
    void reportsUnsupportedComparison() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        // 只有 3 个点，够不到基线长度，DatasetSignal 会返回 null
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(snapshot(3));

        Map<String, Object> result = tools(datasets, mock(HeronDataOpportunityMapper.class))
            .compareSeries("frankfurter-audcny", 7);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) result.get("series")).get(0);
        assertEquals("unsupported", first.get("status"));
        assertNull(first.get("zscore"), "算不出来就不要给数");
    }

    @Test
    @DisplayName("数据源列表只回判断用得上的字段，不回端点与条款")
    void listsSourcesWithoutEndpoint() {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey("frankfurter-audcny");
        source.setName("Frankfurter 汇率");
        source.setCategory("rate");
        source.setCadence("daily");
        source.setEnabled("0");
        source.setCommercialOk("true");
        source.setProbeStatus("ok");
        source.setEndpoint("https://example.invalid/should-not-leak");
        HeronDataSourceMapper sources = mock(HeronDataSourceMapper.class);
        when(sources.selectList(any())).thenReturn(List.of(source));

        HeronResearchTools subject = new HeronResearchTools(
            mock(DatasetFetchService.class), mock(HeronDataOpportunityMapper.class), sources,
            mock(HeronVideoVersionMapper.class));

        Map<String, Object> result = subject.queryDataSources(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("sources")).get(0);
        assertEquals("frankfurter-audcny", item.get("sourceKey"));
        assertEquals(true, item.get("enabled"));
        assertFalse(item.containsKey("endpoint"), "端点不进模型上下文");
        assertFalse(item.containsKey("termsUrl"));
    }

    @Test
    @DisplayName("最近产出：id 转成字符串，避免 Long 精度问题")
    void listsRecentProductions() {
        HeronVideoVersion version = new HeronVideoVersion();
        version.setVersionId(2121151796750819328L);
        version.setWorkId(2121151796750819900L);
        version.setVersionNo(2);
        version.setReviewStatus("approved");
        version.setPublishSource("sandbox");
        HeronVideoVersionMapper videos = mock(HeronVideoVersionMapper.class);
        when(videos.selectList(any())).thenReturn(List.of(version));

        HeronResearchTools subject = new HeronResearchTools(
            mock(DatasetFetchService.class), mock(HeronDataOpportunityMapper.class),
            mock(HeronDataSourceMapper.class), videos);

        Map<String, Object> result = subject.queryRecentProductions(5);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("productions")).get(0);
        assertEquals("2121151796750819328", item.get("versionId"));
        assertEquals(2, item.get("versionNo"));
        assertEquals("approved", item.get("reviewStatus"));
    }
}
