package org.dromara.heron.service.impl;

import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronProductionLane;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronProductionLaneMapper;
import org.dromara.heron.service.impl.shared.production.NewsWorkServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据看板占位候选必须写明来源。
 *
 * <p>source_kind 的列语义是「空=热榜」：占位候选不写来源，效果归因就会把数据看板作品
 * 算成热榜——不报错，只是结论失真（设计文档 §11.6）。读侧分桶已有测试，这里守写侧。
 */
@DisplayName("数据看板占位候选：来源归因")
class NewsPlaceholderSourceKindTest {

    /** JsonUtils 静态初始化要从 Spring 容器拿全局 JsonMapper，给一个最小容器。 */
    @BeforeAll
    static void initializeJsonContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.refresh();
        new SpringUtils().setApplicationContext(context);
    }

    @Test
    @DisplayName("占位候选写入 data_board，不留空被当成热榜")
    @SuppressWarnings("unchecked")
    void placeholderCarriesDataBoardSourceKind() {
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        HeronProductionLaneMapper lanes = mock(HeronProductionLaneMapper.class);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(lanes.lockByType(any())).thenReturn(new HeronProductionLane());
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);
        when(clocks.getIfAvailable(any(Supplier.class))).thenReturn(Clock.systemUTC());
        // 占位落库后让认领在锁候选处停下：本用例只关心插入了什么，不走完整认领。
        when(candidates.lockById(any(Long.class))).thenReturn(null);
        NewsWorkServiceImpl service = new NewsWorkServiceImpl(candidates, null, null, lanes,
            null, null, null, null, null, null, null, clocks, null, null, null, null, null);

        EditorialSelection selection = new EditorialSelection(EditorialSelection.DATA_BOARD, null,
            "econ_gdp", false, "news-data-chart-dark", "rank_competition", List.of());
        assertThrows(HeronAccessException.class, () -> service.claimForAuto(selection, 1L));

        ArgumentCaptor<HeronNewsCandidate> inserted = ArgumentCaptor.forClass(HeronNewsCandidate.class);
        verify(candidates).insert(inserted.capture());
        assertEquals(HeronNewsCandidate.SOURCE_DATA_BOARD, inserted.getValue().getSourceKind(),
            "占位候选来源为空会被列语义归成热榜");
    }
}
