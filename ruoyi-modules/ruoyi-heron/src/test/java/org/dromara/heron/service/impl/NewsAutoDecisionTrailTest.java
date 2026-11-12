package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsWork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 决策落库口径：槽位记最终执行线，pool_json 同时保留原判线与回退原因。
 *
 * <p>这里只验纯逻辑（投影、行选择、trail 形状）。tick 里的调用顺序由
 * {@code NewsAutoTickTest}（mysql 组）覆盖——纯单测里 mock 那条链只会测到 mock 自己。
 */
@DisplayName("自动决策：原判线与执行线都要留痕")
class NewsAutoDecisionTrailTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static HeronNewsCandidate candidate(long id, String title) {
        HeronNewsCandidate item = new HeronNewsCandidate();
        item.setCandidateId(id);
        item.setTitle(title);
        return item;
    }

    private static HeronDataSource dataset(String key, String name) {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey(key);
        source.setName(name);
        source.setCadence("daily");
        source.setShapes("long");
        return source;
    }

    private static EditorialSelection explainer() {
        return new EditorialSelection(EditorialDecisionService.NEWS_EXPLAINER, 101L, null, false,
            "news-9x16", "讲清楚产业影响", List.of("s101"));
    }

    private static EditorialDecisionService.Decision decision(String line) {
        return new EditorialDecisionService.Decision("PUBLISH", line, "值得做", "讲清楚产业影响", null);
    }

    @Test
    @DisplayName("总编辑摘要带 ID，数据源带 key，pool 身份原样透传")
    void poolsProjectionCarriesIdentity() {
        HeronNewsWork recent = new HeronNewsWork();
        recent.setTitle("昨天那条");
        EditorialCandidatePool pool = EditorialCandidatePool.of(
            List.of(candidate(101L, "正文A")), item -> true,
            List.of(dataset("worldbank-gdp", "世界银行 GDP")), List.of(), List.of(recent));

        EditorialDecisionService.Pools pools = NewsAutoService.pools(pool);

        assertEquals(pool.poolId(), pools.poolId());
        assertEquals(pool.poolHash(), pools.poolHash());
        assertEquals(101L, pools.articles().get(0).candidateId());
        assertEquals("正文A", pools.articles().get(0).label());
        assertEquals("worldbank-gdp", pools.datasets().get(0).sourceKey());
        assertTrue(pools.datasets().get(0).label().contains("世界银行 GDP"));
        assertEquals(List.of("昨天那条"), pools.recentPublications());
    }

    @Test
    @DisplayName("回退后槽位记最终线，pool_json 里原判线仍在")
    void trailKeepsOriginalLine() {
        EditorialCandidatePool pool = EditorialCandidatePool.of(
            List.of(candidate(101L, "正文A")), item -> true, List.of(), List.of(), List.of());
        EditorialDecisionService.Pools pools = NewsAutoService.pools(pool);
        EditorialDecisionService.Decision decision = decision(EditorialDecisionService.DATA_BOARD);
        EditorialChoice choice = new EditorialChoice(explainer(), EditorialDecisionService.DATA_BOARD,
            true, "data_board_empty");

        assertEquals(EditorialDecisionService.NEWS_EXPLAINER, NewsAutoService.executedLine(decision, choice));

        JsonNode trail = JSON.readTree(NewsAutoService.decisionTrail(pools, decision, choice));
        // 前端按这 4 个键直接解析，一个都不能少、不能改类型
        assertTrue(trail.path("hotHeadlines").isArray());
        assertTrue(trail.path("articleCandidates").isArray());
        assertTrue(trail.path("dataSources").isArray());
        assertTrue(trail.path("recentPublications").isArray());
        assertEquals("正文A", trail.path("articleCandidates").get(0).asString());
        assertEquals(pool.poolId(), trail.path("poolId").asString());
        assertEquals(pool.poolHash(), trail.path("poolHash").asString());
        assertEquals(EditorialDecisionService.DATA_BOARD, trail.path("decision").path("originalLine").asString());
        assertEquals(EditorialDecisionService.NEWS_EXPLAINER, trail.path("decision").path("finalLine").asString());
        assertTrue(trail.path("decision").path("fallbackApplied").asBoolean());
        assertEquals("data_board_empty", trail.path("decision").path("fallbackReason").asString());
    }

    @Test
    @DisplayName("没回退就不写原因；不发也不留空线")
    void trailWithoutFallback() {
        EditorialCandidatePool pool = EditorialCandidatePool.of(
            List.of(candidate(101L, "正文A")), item -> true, List.of(), List.of(), List.of());
        EditorialDecisionService.Pools pools = NewsAutoService.pools(pool);
        EditorialChoice choice = new EditorialChoice(explainer(), EditorialDecisionService.NEWS_EXPLAINER,
            false, null);

        JsonNode trail = JSON.readTree(NewsAutoService.decisionTrail(pools,
            decision(EditorialDecisionService.NEWS_EXPLAINER), choice));
        assertFalse(trail.path("decision").path("fallbackApplied").asBoolean());
        assertTrue(trail.path("decision").path("fallbackReason").isNull());

        EditorialDecisionService.Decision noPublish =
            new EditorialDecisionService.Decision("NO_PUBLISH", "", "撑不起一条片子", "", null);
        assertEquals(EditorialDecisionService.NO_PUBLISH, NewsAutoService.executedLine(noPublish, null));
        JsonNode noPublishTrail = JSON.readTree(NewsAutoService.decisionTrail(pools, noPublish, null));
        assertEquals(EditorialDecisionService.NO_PUBLISH, noPublishTrail.path("decision").path("originalLine").asString());
        assertEquals(EditorialDecisionService.NO_PUBLISH, noPublishTrail.path("decision").path("finalLine").asString());
    }
}
