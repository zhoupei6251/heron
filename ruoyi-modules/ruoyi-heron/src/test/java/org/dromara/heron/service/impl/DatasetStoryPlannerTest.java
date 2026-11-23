package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronDatasetUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 策划闸：数据层的闸在调模型之前判，故事层的闸在模型输出之后判。 */
@DisplayName("数据看板：策划闸")
class DatasetStoryPlannerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SOURCE_KEY = "sina-gold-xau";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private OpenAiModelClient model;
    private DatasetFetchService datasets;
    private HeronDatasetUsageMapper usageMapper;
    private HeronDataSourceMapper dataSourceMapper;
    private DatasetStoryPlanner planner;
    private ProductionRunVo run;

    @BeforeEach
    void setUp() {
        model = mock(OpenAiModelClient.class);
        datasets = mock(DatasetFetchService.class);
        usageMapper = mock(HeronDatasetUsageMapper.class);
        dataSourceMapper = mock(HeronDataSourceMapper.class);
        planner = new DatasetStoryPlanner(model, datasets, usageMapper, dataSourceMapper);
        run = new ProductionRunVo(0, ProductionBizType.news_video, 0L, 0L, 0, "story-test", "{}", null);
        when(usageMapper.recentSourceKeys(any())).thenReturn(Set.of());
        when(dataSourceMapper.enabledSources()).thenReturn(List.of());
    }

    private static HeronDataSource source() {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey(SOURCE_KEY);
        source.setName("黄金");
        source.setCategory("finance");
        source.setCadence("daily");
        source.setCommercialOk("true");
        return source;
    }

    /** 一份新鲜的十点快照。 */
    private static JsonNode snapshot() {
        return snapshot(LocalDate.now().minusDays(9), "true");
    }

    /** 指定末点日期与许可的快照。 */
    private static JsonNode snapshot(LocalDate lastDate, String commercialOk) {
        int[] values = {100, 98, 102, 105, 108, 110, 112, 115, 121, 120};
        LocalDate start = lastDate.minusDays(values.length - 1L);
        StringBuilder json = new StringBuilder();
        json.append("{\"contract\":\"dataset/1\",\"source_key\":\"").append(SOURCE_KEY).append("\",")
            .append("\"source_name\":\"黄金\",\"shape\":\"wide\",\"cadence\":\"daily\",")
            .append("\"license\":{\"commercial_ok\":\"").append(commercialOk).append("\",\"attribution\":\"公开数据\"},")
            .append("\"metric\":{\"key\":\"xau\",\"label\":\"黄金\",\"unit\":\"元\",\"precision\":2},")
            .append("\"rows\":[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("[\"").append(start.plusDays(index)).append("\",").append(values[index]).append(']');
        }
        json.append("],\"facts\":{")
            .append("\"first\":{\"date\":\"").append(start).append("\",\"value\":100},")
            .append("\"last\":{\"date\":\"").append(lastDate).append("\",\"value\":120},")
            .append("\"min\":{\"date\":\"").append(start.plusDays(1)).append("\",\"value\":98},")
            .append("\"max\":{\"date\":\"").append(start.plusDays(8)).append("\",\"value\":121},")
            .append("\"change\":20,\"change_pct\":0.2,")
            .append("\"period\":{\"start_year\":").append(start.getYear())
            .append(",\"end_year\":").append(lastDate.getYear()).append("}},")
            .append("\"data_sha256\":\"").append(SHA).append("\"}");
        return JSON.readTree(json.toString());
    }

    /** 一份能过契约闸的模型输出。 */
    private static String storyJson() {
        return """
            {
              "narrative": "numeric_contrast",
              "oneLine": "末值与区间峰值只差一点",
              "protagonist": {"kind": "metric", "label": "黄金价格"},
              "tension": {"kind": "gap", "statement": "末值与区间峰值只差一点",
                "evidenceRefs": ["facts.last.value", "facts.max.value"]},
              "claims": [
                {"id": "c1", "text": "区间末值高于区间起点", "kind": "fact",
                  "evidenceRefs": ["facts.last.value"]},
                {"id": "c2", "text": "区间里的峰值出现在靠后一段", "kind": "fact",
                  "evidenceRefs": ["facts.max.value"]},
                {"id": "c3", "text": "后续可能回落到均值附近", "kind": "assumption",
                  "evidenceRefs": []}
              ],
              "payoff": {"text": "差距比看上去更小", "evidenceRefs": ["facts.last.value"]},
              "stakes": {"audience": "持有黄金的账户", "impact": "成本线比想象中更靠近现价"},
              "assumptions": ["后续可能回落到均值附近"]
            }
            """;
    }

    private void replyWith(String json) {
        when(model.chat(any(), anyString(), anyString(), anyString())).thenReturn(json);
    }

    private DatasetStoryPlanner.Result plan(int points) {
        return planner.plan(run, source(), points, "opportunity", 1L, "黄金最近异动", null);
    }

    @Test
    @DisplayName("合规的故事被接受，策划依据由机器填")
    void compliantStoryAccepted() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith(storyJson());

        DatasetStoryPlanner.Result result = plan(10);

        assertTrue(result.accepted(), result.rejectReason());
        EditorialStory story = result.story();
        assertNotNull(story);
        assertEquals(EditorialStory.SCHEMA_VERSION, story.schemaVersion());
        assertEquals("numeric_contrast", story.narrative());
        assertEquals(SHA, story.basis().planningDataSha256());
        assertEquals("opportunity", story.basis().sourceKind());
        assertEquals(1L, story.basis().opportunityId());
        assertEquals(10, story.basis().points());
    }

    @Test
    @DisplayName("数据不新鲜先拒，不调模型")
    void staleDataRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class)))
            .thenReturn(snapshot(LocalDate.now().minusYears(1), "true"));
        replyWith(storyJson());

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_data_stale", result.rejectCode());
    }

    @Test
    @DisplayName("覆盖太低先拒，不调模型")
    void thinDataRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith(storyJson());

        DatasetStoryPlanner.Result result = plan(30);

        assertFalse(result.accepted());
        assertEquals("story_data_thin", result.rejectCode());
    }

    @Test
    @DisplayName("许可不允许商用先拒")
    void blockedLicenseRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class)))
            .thenReturn(snapshot(LocalDate.now().minusDays(9), "false"));
        replyWith(storyJson());

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_license_blocked", result.rejectCode());
    }

    @Test
    @DisplayName("七天里出过片的源不重复讲")
    void duplicateSourceRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        when(usageMapper.recentSourceKeys(any())).thenReturn(Set.of(SOURCE_KEY));
        replyWith(storyJson());

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_duplicate", result.rejectCode());
    }

    @Test
    @DisplayName("规划取数失败先拒")
    void fetchFailureRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class)))
            .thenThrow(new IllegalStateException("取不到"));
        replyWith(storyJson());

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_fetch_failed", result.rejectCode());
    }

    @Test
    @DisplayName("模型输出不是 JSON 就拒")
    void nonJsonReplyRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith("这是一段解释，不是 JSON");

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_contract", result.rejectCode());
    }

    @Test
    @DisplayName("事实主张不足两条就拒")
    void allAssumptionRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith("""
            {
              "narrative": "numeric_contrast",
              "oneLine": "末值与区间峰值只差一点",
              "protagonist": {"kind": "metric", "label": "黄金价格"},
              "tension": {"kind": "gap", "statement": "末值与区间峰值只差一点",
                "evidenceRefs": ["facts.last.value"]},
              "claims": [
                {"id": "c1", "text": "区间末值高于区间起点", "kind": "fact",
                  "evidenceRefs": ["facts.last.value"]},
                {"id": "c2", "text": "后续可能回落到均值附近", "kind": "assumption",
                  "evidenceRefs": []},
                {"id": "c3", "text": "也可能继续走高", "kind": "assumption",
                  "evidenceRefs": []}
              ],
              "payoff": {"text": "差距比看上去更小", "evidenceRefs": ["facts.last.value"]},
              "stakes": {"audience": "持有黄金的账户", "impact": "成本线比想象中更靠近现价"},
              "assumptions": ["后续可能回落到均值附近", "也可能继续走高"]
            }
            """);

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_all_assumption", result.rejectCode());
    }

    @Test
    @DisplayName("说不出谁受影响就拒")
    void missingStakesRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith(storyJson().replace("\"stakes\": {\"audience\": \"持有黄金的账户\", \"impact\": \"成本线比想象中更靠近现价\"},", ""));

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_no_stakes", result.rejectCode());
    }

    @Test
    @DisplayName("故事里出现别的源就拒")
    void crossSourceRejected() {
        HeronDataSource other = new HeronDataSource();
        other.setSourceKey("other-source");
        when(dataSourceMapper.enabledSources()).thenReturn(List.of(source(), other));
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith(storyJson().replace("区间里的峰值出现在靠后一段", "对照 other-source 的口径看更清楚"));

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_needs_multi_source", result.rejectCode());
    }

    @Test
    @DisplayName("硬校验不过就拒，并带上原因")
    void contractViolationRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith(storyJson().replace("区间末值高于区间起点", "区间末值比区间起点高 20 元"));

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_contract", result.rejectCode());
        assertTrue(result.rejectReason().contains("裸数字"), result.rejectReason());
    }

    @Test
    @DisplayName("模型自选一个数据撑不起来的叙事类型就拒")
    void unsupportedNarrativeRejected() {
        when(datasets.snapshot(eq(SOURCE_KEY), any(Integer.class))).thenReturn(snapshot());
        replyWith(storyJson().replace("\"numeric_contrast\"", "\"trend_reversal\"")
            .replace("\"gap\"", "\"trend_break\""));

        DatasetStoryPlanner.Result result = plan(10);

        assertFalse(result.accepted());
        assertEquals("story_contract", result.rejectCode());
        assertTrue(result.rejectReason().contains("没有机器前提"), result.rejectReason());
    }
}
