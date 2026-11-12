package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
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
        item.setCandidateId(id);
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

    private static NewsTemplatePicker templatePicker() {
        NewsTemplatePicker picker = mock(NewsTemplatePicker.class);
        when(picker.pick(any(), any(), anyString())).thenReturn("news-9x16");
        return picker;
    }

    @Test
    @DisplayName("数据看板线：只用池里的数据源，不走 discover/route")
    void dataBoardUsesFrozenDatasets() {
        DatasetRouter router = mock(DatasetRouter.class);
        when(router.pick(any(), any(), anyList())).thenReturn(new DatasetRouter.Route("worldbank-gdp", false));
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), templatePicker(), router);
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
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), templatePicker(), router);
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
        EditorialLineEditor editor = new EditorialLineEditor(mock(NewsCandidateScreener.class), templatePicker(), router);
        HeronNewsCandidate first = article(9L, "稿子A");
        HeronNewsCandidate second = article(10L, "稿子B");
        EditorialCandidatePool pool = EditorialCandidatePool.of(List.of(first, second), item -> true,
            List.of(), List.of(), List.of());

        Optional<EditorialChoice> choice = editor.select(NEWS_EXPLAINER, pool, "讲清楚", 10L);

        assertTrue(choice.isPresent());
        assertEquals(10L, choice.get().selection().candidateId());
        assertFalse(choice.get().fallbackApplied());
    }
}
