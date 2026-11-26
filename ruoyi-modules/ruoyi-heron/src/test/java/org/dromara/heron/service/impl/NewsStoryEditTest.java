package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.bo.NewsStoryBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.IProductionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 人工编辑策划结论：改的是故事，不是选材；改动必须落到新版本的冻结快照里。
 *
 * <p>两条不能破的口径：机器算出来的 basis 人不改；契约不过就当场 409，不静默改写人的结论。
 */
@DisplayName("人工编辑策划结论：只换 story，basis 沿用")
class NewsStoryEditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一份能过闸的规划快照：单指标时间序列，末值高于起点。 */
    private static JsonNode dataset() {
        return JSON.readTree("""
            {
              "contract": "dataset/1",
              "source_key": "sina-gold-xau",
              "source_name": "黄金",
              "shape": "wide",
              "cadence": "daily",
              "rows": [
                ["2026-11-01", 100], ["2026-11-02", 98], ["2026-11-03", 102],
                ["2026-11-04", 105], ["2026-11-05", 108], ["2026-11-06", 110],
                ["2026-11-07", 112], ["2026-11-08", 115], ["2026-11-09", 121],
                ["2026-11-10", 120]
              ],
              "facts": {
                "first": {"date": "2026-11-01", "value": 100},
                "last": {"date": "2026-11-10", "value": 120},
                "min": {"date": "2026-11-02", "value": 98},
                "max": {"date": "2026-11-09", "value": 121},
                "change": 20,
                "change_pct": 0.2,
                "period": {"start_year": 2026, "end_year": 2026}
              },
              "data_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """);
    }

    /** 一份能过闸的故事：数字反差，主张两条事实加一条假设。 */
    private static EditorialStory story() {
        return new EditorialStory(
            "story/1",
            "numeric_contrast",
            "金价十天涨了两次",
            new EditorialStory.Protagonist("metric", "黄金价格", null),
            new EditorialStory.Tension("gap", "末值与区间峰值只差一点",
                List.of("facts.last.value", "facts.max.value")),
            List.of(
                new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact",
                    List.of("facts.last.value")),
                new EditorialStory.Claim("c2", "区间里的峰值出现在靠后一段", "fact",
                    List.of("facts.max.value")),
                new EditorialStory.Claim("c3", "后续可能回落到均值附近", "assumption", List.of())),
            new EditorialStory.Payoff("差距比看上去更小", List.of("facts.last.value")),
            new EditorialStory.Stakes("持有黄金的账户", "成本线比想象中更靠近现价"),
            List.of("后续可能回落到均值附近"),
            new EditorialStory.Basis("opportunity", 0.2, 1.5, false, 1.0, 10,
                "2026-11-01", "2026-11-10", "a".repeat(64), "opportunity", 1L));
    }

    /** 上一版的冻结快照：选材字段与故事都在里面，编辑只该动 story 一个键。 */
    private static Map<String, Object> selection(boolean withStory) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("line", EditorialSelection.DATA_BOARD);
        node.put("dataSourceKey", "sina-gold-xau");
        if (withStory) {
            node.put("story", story().toMap());
        }
        return node;
    }

    private static class Fixture {
        final HeronNewsWorkMapper works = mock(HeronNewsWorkMapper.class);
        final HeronVideoVersionMapper versions = mock(HeronVideoVersionMapper.class);
        final HeronAccessService access = mock(HeronAccessService.class);
        final IProductionService production = mock(IProductionService.class);
        final DatasetFetchService datasets = mock(DatasetFetchService.class);
        final Map<String, Object> frozenSelection;
        final NewsWorkServiceImpl service;

        Fixture(boolean withStory) {
            HeronNewsWork work = new HeronNewsWork();
            work.setId(1L);
            work.setOwnerId(101L);
            work.setCurrentVersionId(2L);
            frozenSelection = selection(withStory);
            HeronVideoVersion version = new HeronVideoVersion();
            version.setId(2L);
            version.setWorkId(1L);
            version.setOwnerId(101L);
            version.setVersionNo(1);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("kind", "data_board");
            snapshot.put("datasetWindow", Map.of("sourceKey", "sina-gold-xau", "points", 10));
            snapshot.put("editorial", Map.of("line", EditorialSelection.DATA_BOARD));
            snapshot.put("selection", frozenSelection);
            version.setInputSnapshot(JSON.writeValueAsString(snapshot));

            when(works.selectById(1L)).thenReturn(work);
            when(versions.selectById(2L)).thenReturn(version);
            when(access.requireActor()).thenReturn(101L);
            when(datasets.snapshot(anyString(), anyInt())).thenReturn(dataset());
            var clock = Clock.fixed(Instant.parse("2026-11-24T08:10:00Z"), ZoneOffset.UTC);
            var clocks = new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(Clock.class);
            service = new NewsWorkServiceImpl(null, works, versions, null, null, access, production, null, null,
                null, clocks, null, new HeronNewsProperties(), null, null, datasets);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capturedStory(ProductionSubmitBo bo) {
        return (Map<String, Object>) bo.override().selectionNode().get("story");
    }

    private static ProductionSubmitBo capture(Fixture fixture) {
        ArgumentCaptor<ProductionSubmitBo> captor = ArgumentCaptor.forClass(ProductionSubmitBo.class);
        verify(fixture.production).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("机器算出来的 basis 人不改：沿用上一版")
    void keepsOriginalBasis() {
        Fixture fixture = new Fixture(true);
        Map<String, Object> edited = story().toMap();
        // 提交里没有 basis，服务必须把上一版的补回去
        edited.remove("basis");

        fixture.service.reproduceWithStory(1L, new NewsStoryBo("edit-1", 1L, edited));

        assertEquals(story().toMap().get("basis"), capturedStory(capture(fixture)).get("basis"));
    }

    @Test
    @DisplayName("只换 story 一个键：选材字段逐字沿用")
    void onlyStoryIsReplaced() {
        Fixture fixture = new Fixture(true);

        fixture.service.reproduceWithStory(1L, new NewsStoryBo("edit-2", 1L, story().toMap()));

        Map<String, Object> frozen = capture(fixture).override().selectionNode();
        assertEquals(Set.of("line", "dataSourceKey", "story"), frozen.keySet());
        assertEquals(EditorialSelection.DATA_BOARD, frozen.get("line"));
        assertEquals("sina-gold-xau", frozen.get("dataSourceKey"));
    }

    @Test
    @DisplayName("契约不过：当场 409，不静默改写人的结论")
    void invalidStoryRejected() {
        Fixture fixture = new Fixture(true);
        Map<String, Object> edited = story().toMap();
        edited.put("claims", List.of());

        HeronBusinessException failure = assertThrows(HeronBusinessException.class,
            () -> fixture.service.reproduceWithStory(1L, new NewsStoryBo("edit-3", 1L, edited)));

        assertEquals("news_story_invalid", failure.getCode());
        assertEquals(409, failure.getStatus());
        verifyNoInteractions(fixture.production);
    }

    @Test
    @DisplayName("上一版没有策划结论：400，不去猜一条")
    void missingStoryRejected() {
        Fixture fixture = new Fixture(false);

        HeronBusinessException failure = assertThrows(HeronBusinessException.class,
            () -> fixture.service.reproduceWithStory(1L, new NewsStoryBo("edit-4", 1L, story().toMap())));

        assertEquals("news_story_not_found", failure.getCode());
        assertEquals(400, failure.getStatus());
        verifyNoInteractions(fixture.production);
    }

    @Test
    @DisplayName("生产线按冻结的线取，不从模板名反推")
    void kindFollowsLine() {
        assertEquals("data_board", NewsProductionDomain.kindOfLine(Map.of("line", EditorialSelection.DATA_BOARD)));
        assertEquals("trend", NewsProductionDomain.kindOfLine(Map.of("line", EditorialSelection.TREND_INSIGHT)));
        assertEquals("knowledge_explainer",
            NewsProductionDomain.kindOfLine(Map.of("line", EditorialSelection.KNOWLEDGE_EXPLAINER)));
        assertEquals("article", NewsProductionDomain.kindOfLine(Map.of("line", EditorialSelection.NEWS_EXPLAINER)));
        assertEquals("article", NewsProductionDomain.kindOfLine(null));
    }

    @Test
    @DisplayName("自动改写只对带策划结论的两条线开，其余线换选题")
    void autoRewriteOnlyForStoryLines() {
        assertTrue(NewsAutoService.supportsStory(EditorialDecisionService.DATA_BOARD));
        assertTrue(NewsAutoService.supportsStory(EditorialDecisionService.TREND_INSIGHT));
        assertFalse(NewsAutoService.supportsStory(EditorialDecisionService.NEWS_EXPLAINER));
        assertFalse(NewsAutoService.supportsStory(EditorialDecisionService.KNOWLEDGE_EXPLAINER));
        assertFalse(NewsAutoService.supportsStory(null));
    }
}
