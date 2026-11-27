package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.lane.databoard.DatasetRouter;
import org.dromara.heron.service.impl.lane.databoard.DatasetStoryPlanner;
import org.dromara.heron.service.impl.shared.research.EditorialDecisionService;
import org.dromara.heron.service.impl.shared.research.EditorialLineEditor;
import org.dromara.heron.service.impl.shared.research.NewsCandidateScreener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 策划在选材这一步的落点：一条不行换下一条，全不行就不出片。 */
@DisplayName("数据看板：策划与选材")
class StoryDecisionTest {

    private NewsCandidateScreener screener;
    private DatasetRouter datasetRouter;
    private DatasetStoryPlanner storyPlanner;
    private EditorialLineEditor editor;

    @BeforeEach
    void setUp() {
        screener = mock(NewsCandidateScreener.class);
        datasetRouter = mock(DatasetRouter.class);
        storyPlanner = mock(DatasetStoryPlanner.class);
        editor = new EditorialLineEditor(screener, datasetRouter, storyPlanner);
    }

    private static HeronDataSource source(String key) {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey(key);
        source.setName(key);
        source.setCategory("finance");
        source.setCadence("daily");
        return source;
    }

    private static HeronDataOpportunity opportunity(long id, String sourceKey) {
        HeronDataOpportunity item = new HeronDataOpportunity();
        item.setId(id);
        item.setSourceKey(sourceKey);
        item.setHeadline("选题 " + id);
        item.setSignals("{\"signal_type\":\"zscore\"}");
        item.setWindowPoints(30);
        item.setShape("wide");
        item.setCadence("daily");
        item.setMetric("xau");
        return item;
    }

    private static EditorialStory story() {
        return new EditorialStory(EditorialStory.SCHEMA_VERSION, "numeric_contrast",
            "末值与区间峰值只差一点",
            new EditorialStory.Protagonist("metric", "黄金价格", null),
            new EditorialStory.Tension("gap", "末值与区间峰值只差一点", List.of("facts.last.value")),
            List.of(new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact",
                List.of("facts.last.value"))),
            new EditorialStory.Payoff("差距比看上去更小", List.of("facts.last.value")),
            new EditorialStory.Stakes("持有黄金的账户", "成本线比想象中更靠近现价"),
            List.of(),
            new EditorialStory.Basis("opportunity", 0.2, 1.5, false, 1.0, 30,
                "2026-11-01", "2026-11-30", "a".repeat(64), "opportunity", 3L));
    }

    /** geo 故事：策划器把 basis.signalType 写成 geo，视觉模式据此转 3D。 */
    private static EditorialStory geoStory() {
        return new EditorialStory(EditorialStory.SCHEMA_VERSION, "numeric_contrast",
            "八座城市的成本差出两倍",
            new EditorialStory.Protagonist("group", "枢纽城市", null),
            new EditorialStory.Tension("gap", "最高与最低差出两倍", List.of("facts.leader.value")),
            List.of(new EditorialStory.Claim("c1", "最高的是上海", "fact", List.of("facts.leader.value"))),
            new EditorialStory.Payoff("关注你所在城市的位置", List.of("facts.leader.value")),
            new EditorialStory.Stakes("发货的人", "成本结构不同"),
            List.of(),
            new EditorialStory.Basis("geo", null, null, null, 1.0, 8,
                null, null, "b".repeat(64), "router", null));
    }

    private static EditorialCandidatePool pool(List<HeronDataSource> datasets,
                                                List<HeronDataOpportunity> opportunities) {
        return new EditorialCandidatePool("pool-1", "a".repeat(64), List.of(), List.of(), datasets,
            List.of(), List.of(), opportunities, List.of());
    }

    @Test
    @DisplayName("前两条机会被拒，第三条成立就选第三条，并留下两次拒绝记录")
    void thirdOpportunityWins() {
        List<HeronDataSource> datasets = List.of(source("src-1"), source("src-2"), source("src-3"));
        List<HeronDataOpportunity> opportunities = List.of(opportunity(1L, "src-1"),
            opportunity(2L, "src-2"), opportunity(3L, "src-3"));
        when(storyPlanner.plan(any(ProductionRunVo.class), any(HeronDataSource.class), anyInt(),
            anyString(), any(), any(), any())).thenAnswer(invocation -> {
                Long opportunityId = invocation.getArgument(4);
                if (Long.valueOf(3L).equals(opportunityId)) {
                    return DatasetStoryPlanner.Result.accepted(story());
                }
                return DatasetStoryPlanner.Result.rejected("story_duplicate", "7 天内用过");
            });

        Optional<EditorialChoice> choice = editor.select(EditorialDecisionService.DATA_BOARD,
            pool(datasets, opportunities), "今天的意图", null, null);

        assertTrue(choice.isPresent());
        EditorialSelection selection = choice.orElseThrow().selection();
        assertEquals(3L, selection.opportunityId());
        assertEquals("src-3", selection.dataSourceKey());
        assertEquals(story().oneLine(), selection.storyAngle());
        List<String> rejects = choice.orElseThrow().storyRejects();
        assertEquals(2, rejects.size(), rejects.toString());
        assertTrue(rejects.contains("src-1:story_duplicate"), rejects.toString());
        assertTrue(rejects.contains("src-2:story_duplicate"), rejects.toString());
    }

    @Test
    @DisplayName("geo 故事声明 3D 风格：2D 画不出经纬度，这条硬事实与渲染方式无关")
    void geoStoryRequestsThreeDStyle() {
        List<HeronDataSource> datasets = List.of(source("geo-src"));
        List<HeronDataOpportunity> opportunities = List.of(opportunity(1L, "geo-src"));
        when(storyPlanner.plan(any(ProductionRunVo.class), any(HeronDataSource.class), anyInt(),
            anyString(), any(), any(), any())).thenReturn(DatasetStoryPlanner.Result.accepted(geoStory()));

        Optional<EditorialChoice> choice = editor.select(EditorialDecisionService.DATA_BOARD,
            pool(datasets, opportunities), "今天的意图", null, null);

        assertTrue(choice.isPresent());
        assertEquals(EditorialSelection.LineStyle.DATA_BOARD_3D, choice.orElseThrow().selection().templateId());
    }

    @Test
    @DisplayName("总编辑锁定机会时只试锁定的那条，不擅自换题")
    void lockedOpportunityOnlyTriedOnce() {
        List<HeronDataSource> datasets = List.of(source("src-1"), source("src-2"));
        List<HeronDataOpportunity> opportunities = List.of(opportunity(1L, "src-1"),
            opportunity(2L, "src-2"));
        when(storyPlanner.plan(any(ProductionRunVo.class), any(HeronDataSource.class), anyInt(),
            anyString(), any(), any(), any()))
            .thenReturn(DatasetStoryPlanner.Result.rejected("story_signal_weak", "没强信号"));
        when(datasetRouter.pick(any(ProductionRunVo.class), any(), any()))
            .thenThrow(new IllegalStateException("没有可用源"));

        Optional<EditorialChoice> choice = editor.select(EditorialDecisionService.DATA_BOARD,
            pool(datasets, opportunities), "今天的意图", null, 2L);

        assertTrue(choice.isEmpty());
        verify(storyPlanner, times(1)).plan(any(ProductionRunVo.class), any(HeronDataSource.class),
            anyInt(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("机会都不成立时，连兜底速览也要有故事")
    void digestFallbackAlsoNeedsStory() {
        List<HeronDataSource> datasets = List.of(source("src-1"));
        when(datasetRouter.pick(any(ProductionRunVo.class), any(), any()))
            .thenReturn(new DatasetRouter.Route("src-1", true));
        when(storyPlanner.plan(any(ProductionRunVo.class), any(HeronDataSource.class), anyInt(),
            anyString(), any(), any(), any()))
            .thenReturn(DatasetStoryPlanner.Result.rejected("story_signal_weak", "没强信号"));

        Optional<EditorialSelection> selection = editor.editDataBoard(datasets, List.of(), null);

        assertTrue(selection.isEmpty());
    }

    @Test
    @DisplayName("没故事就不占看板槽位")
    void noStoryMeansNoDataBoardSlot() {
        List<HeronDataSource> datasets = List.of(source("src-1"));
        List<HeronDataOpportunity> opportunities = List.of(opportunity(1L, "src-1"));
        when(storyPlanner.plan(any(ProductionRunVo.class), any(HeronDataSource.class), anyInt(),
            anyString(), any(), any(), any()))
            .thenReturn(DatasetStoryPlanner.Result.rejected("story_all_assumption", "只能靠假设"));
        when(datasetRouter.pick(any(ProductionRunVo.class), any(), any()))
            .thenThrow(new IllegalStateException("没有可用源"));

        Optional<EditorialSelection> selection = editor.editDataBoard(datasets, opportunities, null);

        assertTrue(selection.isEmpty());
    }

    @Test
    @DisplayName("故事成立时选材要带上策划结论")
    void acceptedStoryTravelsWithSelection() {
        List<HeronDataSource> datasets = List.of(source("src-1"));
        List<HeronDataOpportunity> opportunities = List.of(opportunity(1L, "src-1"));
        when(storyPlanner.plan(any(ProductionRunVo.class), any(HeronDataSource.class), anyInt(),
            anyString(), any(), any(), any()))
            .thenReturn(DatasetStoryPlanner.Result.accepted(story()));

        Optional<EditorialSelection> selection = editor.editDataBoard(datasets, opportunities, null);

        assertTrue(selection.isPresent());
        assertEquals(EditorialStory.SCHEMA_VERSION, selection.orElseThrow().story().schemaVersion());
        assertEquals(1L, selection.orElseThrow().opportunityId());
    }
}
