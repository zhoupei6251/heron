package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 线内编辑只读建池时冻结的候选：数据源不再二次查库，回退必须留下原因。
 * 这是「总编辑看到的」与「实际被挑的」同一份数据在代码层的落点。
 */
@DisplayName("线内编辑：只读冻结候选、回退留痕")
class EditorialLineEditorFrozenPoolTest {

    private static final String DATA_BOARD = EditorialDecisionService.DATA_BOARD;
    private static final String NEWS_EXPLAINER = EditorialDecisionService.NEWS_EXPLAINER;

    private static HeronNewsCandidate article(long id, String title) {
        HeronNewsCandidate item = new HeronNewsCandidate();
        item.setId(id);
        item.setTitle(title);
        item.setSummary("摘要");
        item.setEvidence("{\"items\":[{\"sourceItemId\":\"s" + id + "\",\"sourceUrl\":\"https://example.com/" + id + "\"}]}");
        return item;
    }

    private static HeronDataSource dataset(String key) {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey(key);
        source.setName("源-" + key);
        source.setCadence("daily");
        source.setShapes("long");
        return source;
    }

    /**
     * 看板线「没故事就不做」：选源器兜底出来的速览同样要先有一份策划结论。
     * 这里只桩住通过路径，被否掉的路径由 StoryDecisionTest 覆盖。
     */
    private static DatasetStoryPlanner storyPlanner() {
        DatasetStoryPlanner planner = mock(DatasetStoryPlanner.class);
        when(planner.plan(any(), any(), anyInt(), anyString(), any(), any(), any()))
            .thenReturn(DatasetStoryPlanner.Result.accepted(story()));
        return planner;
    }

    private static EditorialStory story() {
        return new EditorialStory(EditorialStory.SCHEMA_VERSION, "numeric_contrast", "增速明显高于常态",
            new EditorialStory.Protagonist("metric", "国内生产总值", null),
            new EditorialStory.Tension("gap", "增速远超常态", List.of("facts.last.value")),
            List.of(new EditorialStory.Claim("c1", "增速连续走高", "fact", List.of("facts.last.value"))),
            new EditorialStory.Payoff("需要留意后续回落", List.of("facts.last.value")),
            new EditorialStory.Stakes("关注经济的家庭", "预算安排需要复核"),
            List.of(),
            new EditorialStory.Basis("mover", 0.2, null, false, 1.0, 10, "2026-01-01", "2026-01-10",
                "a".repeat(64), "opportunity", 900L));
    }

    @Test
    @DisplayName("数据看板线：只用池里的数据源，不走 discover/route")
    void dataBoardUsesFrozenDatasets() {
        DatasetRouter router = mock(DatasetRouter.class);
        when(router.pick(any(), any(), anyList())).thenReturn(new DatasetRouter.Route("worldbank-gdp", false));
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(dataset("worldbank-gdp")), List.of(), List.of());

        Optional<EditorialChoice> choice = editor.select(DATA_BOARD, pool, "讲数据", null);

        assertTrue(choice.isPresent());
        assertFalse(choice.get().fallbackApplied());
        assertEquals(DATA_BOARD, choice.get().finalLine());
        assertEquals("worldbank-gdp", choice.get().selection().dataSourceKey());
        verify(router, never()).route(any(), any());
        verify(router, times(1)).pick(any(), any(), anyList());
    }

    @Test
    @DisplayName("数据看板无源：回退解说线并写明原因")
    void dataBoardEmptyFallsBackWithReason() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(), List.of(), List.of());

        Optional<EditorialChoice> choice = editor.select(DATA_BOARD, pool, "讲数据", null);

        assertTrue(choice.isPresent());
        assertTrue(choice.get().fallbackApplied());
        assertEquals("data_board_empty", choice.get().fallbackReason());
        assertEquals(NEWS_EXPLAINER, choice.get().finalLine());
        verify(router, never()).route(any(), any());
        verify(router, never()).pick(any(), any(), anyList());
    }

    @Test
    @DisplayName("总编辑锁定候选：解说线直接用那一条")
    void honoursFocusCandidate() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        HeronNewsCandidate first = article(9L, "稿子A");
        HeronNewsCandidate second = article(10L, "稿子B");
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(first, second), item -> true,
            List.of(), List.of(), List.of());

        Optional<EditorialChoice> choice = editor.select(NEWS_EXPLAINER, pool, "讲清楚", 10L);

        assertTrue(choice.isPresent());
        assertEquals(10L, choice.get().selection().candidateId());
        assertFalse(choice.get().fallbackApplied());
    }

    @Test
    @DisplayName("总编辑意图进选源上下文：选源器看得到今天要做什么方向")
    void intentReachesRouterContext() {
        DatasetRouter router = mock(DatasetRouter.class);
        when(router.pick(any(), any(), anyList())).thenReturn(new DatasetRouter.Route("worldbank-gdp", false));
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(), item -> true,
            List.of(dataset("worldbank-gdp")), List.of(), List.of());

        editor.select(DATA_BOARD, pool, "用 3D 地球呈现全球地震分布", null);

        org.mockito.ArgumentCaptor<tools.jackson.databind.JsonNode> input =
            org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(router).pick(any(), input.capture(), anyList());
        assertEquals("用 3D 地球呈现全球地震分布", input.getValue().path("title").asString());
    }

    @Test
    @DisplayName("数据线回退解说线：不带旧方向的角度，geo 意图不能塞进新闻片")
    void crossLineFallbackDropsStaleIntent() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(article(9L, "稿子")), item -> true,
            List.of(), List.of(), List.of());

        Optional<EditorialChoice> choice = editor.select(DATA_BOARD, pool, "用 3D 地球呈现全球地震分布", null);

        assertTrue(choice.isPresent());
        assertTrue(choice.get().fallbackApplied());
        assertEquals(NEWS_EXPLAINER, choice.get().finalLine());
        assertEquals("", choice.get().selection().storyAngle());
    }

    private static HeronDataOpportunity opportunity(long id, String sourceKey) {
        HeronDataOpportunity item = new HeronDataOpportunity();
        item.setId(id);
        item.setSourceKey(sourceKey);
        item.setHeadline("Frankfurter 近 7 日 -2.39%");
        item.setScore(java.math.BigDecimal.valueOf(69.94));
        return item;
    }

    @Test
    @DisplayName("总编辑锁定数据机会：直接用它的源，不再让选源器重挑")
    void focusedOpportunityLocksItsSource() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(), item -> true,
            List.of(dataset("frankfurter-audcny")), List.of(), List.of(),
            List.of(opportunity(900L, "frankfurter-audcny")));

        Optional<EditorialChoice> choice = editor.select(DATA_BOARD, pool, "讲人民币", null, 900L);

        assertTrue(choice.isPresent());
        assertEquals("frankfurter-audcny", choice.get().selection().dataSourceKey());
        assertFalse(choice.get().selection().digest(), "被机会选中的源不是「没题材时的速览」");
        assertEquals(900L, choice.get().selection().opportunityId(), "要记住这条片子的来源机会，产出后好置 used");
        assertNotNull(choice.get().selection().story(), "看板线的选材必须带策划结论");
        assertFalse(choice.get().fallbackApplied());
        verify(router, never()).pick(any(), any(), anyList());
    }

    @Test
    @DisplayName("只有数据机会、没有数据源清单：出不了策划结论，看板线就不占槽位")
    void opportunityWithoutSourceKeepsDataBoardEmpty() {
        DatasetRouter router = mock(DatasetRouter.class);
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), router, storyPlanner());
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(), item -> true,
            List.of(), List.of(), List.of(), List.of(opportunity(900L, "frankfurter-audcny")));

        Optional<EditorialChoice> choice = editor.select(DATA_BOARD, pool, "讲人民币", null);

        assertTrue(choice.isEmpty(), "找不到数据源就出不了策划结论，这条线不该占槽位");
        verify(router, never()).pick(any(), any(), anyList());
    }
}
