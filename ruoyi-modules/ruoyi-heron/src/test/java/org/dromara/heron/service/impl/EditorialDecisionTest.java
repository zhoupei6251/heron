package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 总编辑只选线，且不许选没有素材的线。 */
@DisplayName("总编辑：走哪条线或不发")
class EditorialDecisionTest {

    private static EditorialDecisionService.Pools pools(List<String> hot, List<String> articles,
                                                        List<String> data) {
        List<EditorialDecisionService.CandidateRef> hotRefs = refs(hot);
        List<EditorialDecisionService.CandidateRef> articleRefs = refs(articles);
        List<EditorialDecisionService.DatasetRef> dataRefs = data.stream()
            .map(key -> new EditorialDecisionService.DatasetRef(key, key + "（daily，long）")).toList();
        return new EditorialDecisionService.Pools("pool-1", "0".repeat(64),
            hotRefs, articleRefs, dataRefs, List.of("昨天：中美科技市值榜"));
    }

    private static List<EditorialDecisionService.CandidateRef> refs(List<String> titles) {
        return java.util.stream.IntStream.range(0, titles.size())
            .mapToObj(index -> new EditorialDecisionService.CandidateRef(100L + index, titles.get(index)))
            .toList();
    }

    @Test
    @DisplayName("走数据看板：line 与 intent 都要原样保留")
    void publishesToDataBoard() {
        var decision = EditorialDecisionService.plan("""
            {"decision":"PUBLISH","selectedLine":"DATA_BOARD",
             "reason":"今天有强对比价值的产业数据","editorialIntent":"看全球新能源份额怎么变"}
            """, pools(List.of("芯片涨价"), List.of("某篇稿子"), List.of("owid-population-race")));
        assertTrue(decision.publish());
        assertEquals(EditorialDecisionService.DATA_BOARD, decision.selectedLine());
        assertEquals("看全球新能源份额怎么变", decision.editorialIntent());
        assertNull(decision.focusCandidateId(), "没给 focusCandidateId 时保持原行为");
    }

    @Test
    @DisplayName("带 ``` 围栏的 JSON 也要认得")
    void acceptsFencedJson() {
        var decision = EditorialDecisionService.plan("""
            ```json
            {"decision":"PUBLISH","selectedLine":"NEWS_EXPLAINER",
             "reason":"重大事件","editorialIntent":"解释产业影响"}
            ```
            """, pools(List.of("事件"), List.of("正文齐全的稿子"), List.of()));
        assertEquals(EditorialDecisionService.NEWS_EXPLAINER, decision.selectedLine());
    }

    @Test
    @DisplayName("没有数据集时不许走数据看板")
    void rejectsDataBoardWithoutData() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("""
                {"decision":"PUBLISH","selectedLine":"DATA_BOARD",
                 "reason":"想讲数据","editorialIntent":"讲点什么"}
                """, pools(List.of("热榜"), List.of("有正文"), List.of())));
        assertEquals("editorial_pool_empty", error.getCode());
    }

    @Test
    @DisplayName("走趋势洞察：line 原样保留，不许被并成数据看板")
    void publishesToTrendInsight() {
        var decision = EditorialDecisionService.plan("""
            {"decision":"PUBLISH","selectedLine":"TREND_INSIGHT",
             "reason":"金价连续三个月同向抬升","editorialIntent":"讲这段抬升意味着什么"}
            """, pools(List.of("金价新高"), List.of(), List.of("sample-gold")));
        assertTrue(decision.publish());
        assertEquals(EditorialDecisionService.TREND_INSIGHT, decision.selectedLine());
        assertEquals("讲这段抬升意味着什么", decision.editorialIntent());
    }

    @Test
    @DisplayName("趋势线与看板线共用数据池：没有数据也不许走趋势")
    void rejectsTrendInsightWithoutData() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("""
                {"decision":"PUBLISH","selectedLine":"TREND_INSIGHT",
                 "reason":"想讲趋势","editorialIntent":"讲变化"}
                """, pools(List.of("热榜"), List.of("有正文"), List.of())));
        assertEquals("editorial_pool_empty", error.getCode());
        assertTrue(error.getMessage().contains("趋势"), error.getMessage());
    }

    @Test
    @DisplayName("没有带正文的文章时不许走解说线——热榜标题不算正文")
    void rejectsExplainerWithoutArticles() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("""
                {"decision":"PUBLISH","selectedLine":"NEWS_EXPLAINER",
                 "reason":"热点很热","editorialIntent":"讲热点"}
                """, pools(List.of("微博热搜标题"), List.of(), List.of("owid-population-race"))));
        assertEquals("editorial_pool_empty", error.getCode());
    }

    @Test
    @DisplayName("不发是合法结果")
    void noPublish() {
        var decision = EditorialDecisionService.plan("""
            {"decision":"NO_PUBLISH","reason":"候选都撑不起一条片子"}
            """, pools(List.of(), List.of(), List.of()));
        assertFalse(decision.publish());
        assertEquals("候选都撑不起一条片子", decision.reason());
    }

    @Test
    @DisplayName("缺字段与无法解析一律拒绝，不猜")
    void rejectsIncomplete() {
        var empty = pools(List.of("热榜"), List.of("稿子"), List.of("源"));
        assertEquals("editorial_decision_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("不是 JSON", empty)).getCode());
        assertEquals("editorial_decision_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("{\"decision\":\"PUBLISH\"}", empty)).getCode());
        assertEquals("editorial_decision_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan(
                "{\"decision\":\"PUBLISH\",\"selectedLine\":\"TELEVISION\"}", empty)).getCode());
        assertEquals("editorial_decision_invalid", assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("{\"decision\":\"NO_PUBLISH\"}", empty)).getCode());
    }

    @Test
    @DisplayName("总编辑锁定候选：ID 在池子里就原样保留")
    void keepsKnownFocusCandidate() {
        var decision = EditorialDecisionService.plan("""
            {"decision":"PUBLISH","selectedLine":"NEWS_EXPLAINER","focusCandidateId":101,
             "reason":"这条最完整","editorialIntent":"解释产业影响"}
            """, pools(List.of("热榜"), List.of("正文A", "正文B"), List.of()));
        assertEquals(101L, decision.focusCandidateId());
    }

    @Test
    @DisplayName("总编辑编了一个候选 ID：拒绝，不猜是哪条")
    void rejectsUnknownFocusCandidate() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> EditorialDecisionService.plan("""
                {"decision":"PUBLISH","selectedLine":"NEWS_EXPLAINER","focusCandidateId":999,
                 "reason":"这条最完整","editorialIntent":"解释产业影响"}
                """, pools(List.of("热榜"), List.of("正文A"), List.of())));
        assertEquals("editorial_decision_invalid", error.getCode());
    }

    @Test
    @DisplayName("候选摘要带 ID：模型才能回指到具体候选")
    void promptCarriesIds() {
        String prompt = EditorialDecisionService.prompt(
            pools(List.of("热榜标题"), List.of("正文稿"), List.of("worldbank-gdp")));
        assertTrue(prompt.contains("[100] 热榜标题"), prompt);
        assertTrue(prompt.contains("[100] 正文稿"), prompt);
        assertTrue(prompt.contains("worldbank-gdp"), prompt);
    }

    private static EditorialDecisionService.Pools withOpportunities(List<String> data, List<String> labels) {
        List<EditorialDecisionService.OpportunityRef> opportunities =
            java.util.stream.IntStream.range(0, labels.size())
                .mapToObj(index -> new EditorialDecisionService.OpportunityRef(900L + index, labels.get(index)))
                .toList();
        List<EditorialDecisionService.DatasetRef> dataRefs = data.stream()
            .map(key -> new EditorialDecisionService.DatasetRef(key, key)).toList();
        return new EditorialDecisionService.Pools("pool-1", "0".repeat(64),
            List.of(), List.of(), dataRefs, opportunities, List.of());
    }

    @Test
    @DisplayName("数据机会带编号和分数进提示词：没有数字就判断不了值不值得做")
    void promptCarriesOpportunityNumbers() {
        String prompt = EditorialDecisionService.prompt(
            withOpportunities(List.of(), List.of("Frankfurter 近 7 日 -2.39%，创窗口新低（3.6σ），分 69.94")));

        assertTrue(prompt.contains("[900]"), prompt);
        assertTrue(prompt.contains("3.6σ"), prompt);
        assertTrue(prompt.contains("69.94"), prompt);
        assertTrue(prompt.contains("数据机会"), prompt);
    }

    @Test
    @DisplayName("只有数据机会、没有数据源清单时，数据看板线仍然可选")
    void dataBoardAllowedByOpportunityAlone() {
        var decision = EditorialDecisionService.plan("""
            {"decision":"PUBLISH","selectedLine":"DATA_BOARD",
             "reason":"人民币走强值得讲","editorialIntent":"看人民币对主要货币的变化"}
            """, withOpportunities(List.of(), List.of("Frankfurter 近 7 日 -2.39%，分 69.94")));

        assertTrue(decision.publish());
        assertEquals(EditorialDecisionService.DATA_BOARD, decision.selectedLine());
    }

    @Test
    @DisplayName("既没机会也没数据集：数据看板线不许选")
    void dataBoardRejectedWithoutAnyMaterial() {
        var error = assertThrows(org.dromara.heron.domain.HeronBusinessException.class,
            () -> EditorialDecisionService.plan("""
                {"decision":"PUBLISH","selectedLine":"DATA_BOARD",
                 "reason":"随便做一条","editorialIntent":"数据"}
                """, withOpportunities(List.of(), List.of())));
        assertEquals("editorial_pool_empty", error.getCode());
    }

    @Test
    @DisplayName("锁定的数据机会必须真的在池子里")
    void unknownFocusOpportunityRejected() {
        var error = assertThrows(org.dromara.heron.domain.HeronBusinessException.class,
            () -> EditorialDecisionService.plan("""
                {"decision":"PUBLISH","selectedLine":"DATA_BOARD","focusOpportunityId":123,
                 "reason":"就做这条","editorialIntent":"数据"}
                """, withOpportunities(List.of(), List.of("Frankfurter 近 7 日 -2.39%，分 69.94"))));
        assertEquals("editorial_decision_invalid", error.getCode());
    }

    @Test
    @DisplayName("锁定的数据机会被原样保留，交给线内编辑直接锁源")
    void focusOpportunityKept() {
        var decision = EditorialDecisionService.plan("""
            {"decision":"PUBLISH","selectedLine":"DATA_BOARD","focusOpportunityId":900,
             "reason":"就做这条","editorialIntent":"数据"}
            """, withOpportunities(List.of(), List.of("Frankfurter 近 7 日 -2.39%，分 69.94")));

        assertEquals(900L, decision.focusOpportunityId());
    }
}
