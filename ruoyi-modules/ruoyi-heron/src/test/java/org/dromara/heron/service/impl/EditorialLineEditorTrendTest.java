package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 趋势线的选源：只认能算出机器判定的机会。
 *
 * <p>这里防的是「有数据就升趋势」——形态不是时间序列、点数不够的机会，
 * 机器只能给「样本不足」，做出来就是一条假装成趋势的数据看板，
 * 还占掉一个本该给真趋势的槽位。
 */
@DisplayName("线内编辑：趋势线只认可判定的机会")
class EditorialLineEditorTrendTest {

    private static final String TREND = EditorialDecisionService.TREND_INSIGHT;
    private static final String DATA_BOARD = EditorialDecisionService.DATA_BOARD;

    private static HeronNewsCandidate article(long id, String title) {
        HeronNewsCandidate item = new HeronNewsCandidate();
        item.setId(id);
        item.setTitle(title);
        item.setSummary("摘要");
        item.setEvidence("{\"items\":[{\"sourceItemId\":\"s" + id + "\",\"sourceUrl\":\"https://example.com/" + id + "\"}]}");
        return item;
    }

    private static HeronDataSource dataset(String key, String shape) {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey(key);
        source.setName("源-" + key);
        source.setCadence("daily");
        source.setShapes(shape);
        return source;
    }

    private static HeronDataOpportunity opportunity(long id, String sourceKey, String shape, int windowPoints,
                                                   String cadence) {
        HeronDataOpportunity item = new HeronDataOpportunity();
        item.setId(id);
        item.setSourceKey(sourceKey);
        item.setHeadline("金价连涨三月");
        item.setMetric("gold_close");
        item.setShape(shape);
        item.setCadence(cadence);
        item.setWindowPoints(windowPoints);
        item.setScore(new BigDecimal("88.5"));
        item.setSignals("{\"type\":\"sustained_shift\",\"direction\":\"up\"}");
        item.setSnapshotJson("{\"data_sha256\":\"sha-gold\"}");
        return item;
    }

    /**
     * 看板线现在「没故事就不做」：策划器必须给出通过的故事，机会才认。
     * 这里只桩住「通过」这一条路径，被否掉的路径由 StoryDecisionTest 单独覆盖。
     */
    private static DatasetStoryPlanner storyPlanner() {
        DatasetStoryPlanner planner = mock(DatasetStoryPlanner.class);
        when(planner.plan(any(), any(), anyInt(), anyString(), any(), any(), any()))
            .thenReturn(DatasetStoryPlanner.Result.accepted(story()));
        return planner;
    }

    private static EditorialStory story() {
        return new EditorialStory(EditorialStory.SCHEMA_VERSION, "numeric_contrast", "金价涨势超出常态",
            new EditorialStory.Protagonist("metric", "黄金价格", null),
            new EditorialStory.Tension("gap", "涨幅远超常态", List.of("facts.last.value")),
            List.of(new EditorialStory.Claim("c1", "价格连续上行", "fact", List.of("facts.last.value"))),
            new EditorialStory.Payoff("需要留意回撤", List.of("facts.last.value")),
            new EditorialStory.Stakes("持有黄金的账户", "资产配置需要复核"),
            List.of(),
            new EditorialStory.Basis("mover", 0.2, null, false, 1.0, 10, "2026-01-01", "2026-01-10",
                "a".repeat(64), "opportunity", 11L));
    }

    @Test
    @DisplayName("趋势线：wide 且点数够的机会被选中，角度落到「变化意味着什么」")
    void trendPicksRoutableOpportunity() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class),
            router, storyPlanner());
        HeronDataOpportunity routable = opportunity(11L, "sample-gold", "wide", 12, "daily");
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(dataset("sample-gold", "wide"), dataset("worldbank-gdp", "rows")),
            List.of(), List.of(), List.of(routable));

        Optional<EditorialChoice> choice = editor.select(TREND, pool, "讲金价的变化意味着什么", null);

        assertTrue(choice.isPresent());
        assertFalse(choice.get().fallbackApplied());
        assertEquals(TREND, choice.get().finalLine());
        EditorialSelection selection = choice.get().selection();
        assertEquals("sample-gold", selection.dataSourceKey());
        assertEquals(EditorialSelection.LineStyle.TREND, selection.templateId(), "过渡值：线内编辑只给风格 id");
        assertTrue(selection.trendInsight());
        // 机会本身要冻结进输入，写稿与质量审稿都靠它对齐取数口径
        assertNotNull(selection.opportunity());
        assertEquals(11L, selection.opportunity().opportunityId());
        assertEquals("wide", selection.opportunity().shape());
        assertEquals(12, selection.opportunity().windowPoints());
        assertEquals("sha-gold", selection.opportunity().expectedDataSha256());
        // 角度必须带趋势尾巴，否则写稿只会复述快照
        assertTrue(selection.storyAngle().contains("变化意味着什么"), selection.storyAngle());
        verify(router, never()).pick(any(), any(), anyList());
    }

    @Test
    @DisplayName("趋势线：rows 形态或点数不足的机会一律不选，走选源器兜底")
    void trendSkipsNonRoutableOpportunity() {
        DatasetRouter router = mock(DatasetRouter.class);
        when(router.pick(any(), any(), anyList())).thenReturn(new DatasetRouter.Route("worldbank-gdp", false));
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class),
            router, storyPlanner());
        HeronDataOpportunity tooShort = opportunity(12L, "sample-gold", "wide", 5, "daily");
        HeronDataOpportunity rows = opportunity(13L, "sample-rows", "rows", 40, "daily");
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(dataset("worldbank-gdp", "wide")),
            List.of(), List.of(), List.of(tooShort, rows));

        Optional<EditorialChoice> choice = editor.select(TREND, pool, "看看有没有值得讲的趋势", null);

        assertTrue(choice.isPresent());
        assertEquals("worldbank-gdp", choice.get().selection().dataSourceKey(),
            "不可判定的机会不能占趋势槽位");
        assertEquals(null, choice.get().selection().opportunityId());
        verify(router, times(1)).pick(any(), any(), anyList());
    }

    @Test
    @DisplayName("看板线不受趋势门槛限制：同一个短序列机会，出得了策划结论就用它")
    void dataBoardStillTakesShortSeriesOpportunity() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class),
            router, storyPlanner());
        HeronDataOpportunity tooShort = opportunity(12L, "sample-gold", "wide", 5, "daily");
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(dataset("sample-gold", "wide")),
            List.of(), List.of(), List.of(tooShort));

        Optional<EditorialChoice> choice = editor.select(DATA_BOARD, pool, "讲讲这几个数", null);

        assertTrue(choice.isPresent());
        assertEquals(DATA_BOARD, choice.get().finalLine());
        assertEquals("sample-gold", choice.get().selection().dataSourceKey());
        assertEquals(12L, choice.get().selection().opportunityId(),
            "看板线只要求有数据，趋势门槛不该外溢到这里");
        assertNotNull(choice.get().selection().story(), "看板线的选材必须带策划结论");
        verify(router, never()).pick(any(), any(), anyList());
    }

    @Test
    @DisplayName("趋势线没有可判定机会也没有数据源：回退解说线并写明 trend_empty")
    void trendEmptyFallsBackWithReason() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class),
            router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(), List.of(), List.of(),
            List.of(opportunity(14L, "sample-attention", "rows", 30, "daily")));

        Optional<EditorialChoice> choice = editor.select(TREND, pool, "做趋势", null);

        assertTrue(choice.isPresent());
        assertTrue(choice.get().fallbackApplied());
        assertEquals(EditorialDecisionService.NEWS_EXPLAINER, choice.get().finalLine());
        assertEquals("trend_empty", choice.get().fallbackReason());
    }
}
