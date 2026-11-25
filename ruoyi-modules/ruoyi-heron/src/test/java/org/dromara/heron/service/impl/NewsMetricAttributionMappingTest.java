package org.dromara.heron.service.impl;

import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 冻结输入与表现归因之间的字段契约。
 *
 * <p>归因全靠从冻结输入里按路径取值：路径一旦对不上，不会报错，只会静悄悄归到「未记录」。
 * 那种失真最难发现——页面照常出结论，只是结论跟创作没关系。
 *
 * <p>所以这条用例不用手抄的样例快照，而是拿生产真实的节点构造器造冻结输入，
 * 再拿真实的取值器读回来：写入方改了字段名，这里立刻红。
 */
@DisplayName("冻结输入与表现归因：字段路径一致")
class NewsMetricAttributionMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 数据看板线的模板。 */
    private static final String TEMPLATE_ID = "news-data-chart-dark";

    /** 数据看板线绑的数据源。 */
    private static final String SOURCE_KEY = "econ_gdp";

    /**
     * JsonUtils 静态初始化时要从 Spring 容器里拿全局 JsonMapper，
     * 这里给一个只注册了 JsonMapper 的最小容器。
     */
    @BeforeAll
    static void initializeJsonContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.refresh();
        new SpringUtils().setApplicationContext(context);
    }

    @Test
    @DisplayName("数据看板线：模板、线、角度、子线、数据源、信号都取得到")
    void readsEveryAttributeOfDataBoardVideo() {
        EditorialSelection selection = dataBoardSelection();
        String input = JSON.writeValueAsString(frozenInput(selection));

        MetricInsightAnalyzer.Sample sample = read(input);

        assertEquals(TEMPLATE_ID, sample.templateId(), "模板取不到");
        assertEquals(EditorialSelection.DATA_BOARD, sample.line(), "生产线取不到");
        assertEquals("rank_competition", sample.angle(), "叙事角度取不到");
        assertEquals("data_board", sample.kind(), "子线取不到");
        assertEquals(SOURCE_KEY, sample.dataSourceKey(), "数据源取不到");
        assertEquals("zscore_outlier", sample.signalType(), "机会信号取不到");
        assertEquals(42L, sample.candidateId(), "候选取不到");
        assertEquals("bar", sample.chart(), "图表形态取不到");
    }

    @Test
    @DisplayName("解说线：没有机会也照样能归因，只有信号是未记录")
    void readsExplainerVideoWithoutOpportunity() {
        EditorialSelection selection = new EditorialSelection(EditorialSelection.NEWS_EXPLAINER, 42L,
            null, false, "news-article-dark", "time_change", List.of("ev-1"));
        String input = JSON.writeValueAsString(frozenInput(selection));

        MetricInsightAnalyzer.Sample sample = read(input);

        assertEquals("article", sample.kind(), "子线取不到");
        assertEquals(EditorialSelection.NEWS_EXPLAINER, sample.line());
        assertEquals("news-article-dark", sample.templateId());
        assertEquals("time_change", sample.angle());
        assertNull(sample.signalType(), "没有机会就没有信号，不许猜");
        assertEquals(MetricInsightAnalyzer.UNKNOWN,
            MetricInsightAnalyzer.bucketFor("signal", sample), "缺信号要如实归到未记录");
    }

    @Test
    @DisplayName("信号只认 editorial 节点：挪到 selection 底下就读不到了")
    void signalMustSitUnderEditorialNode() {
        Map<String, Object> snapshot = frozenInput(dataBoardSelection());
        @SuppressWarnings("unchecked")
        Map<String, Object> editorial = (Map<String, Object>) snapshot.get("editorial");
        @SuppressWarnings("unchecked")
        Map<String, Object> selection = (Map<String, Object>) snapshot.get("selection");
        Object signals = editorial.remove("opportunitySignals");
        selection.put("opportunitySignals", signals);

        MetricInsightAnalyzer.Sample sample = read(JSON.writeValueAsString(snapshot));

        assertNull(sample.signalType(), "信号只从 editorial 节点读，位置错了就是读不到");
        assertEquals(MetricInsightAnalyzer.UNKNOWN,
            MetricInsightAnalyzer.bucketFor("signal", sample), "读不到就要归未记录，不许换个地方凑");
    }

    @Test
    @DisplayName("取不到的属性一律归未记录，不猜")
    void missingAttributesFallBackToUnknown() {
        MetricInsightAnalyzer.Sample sample = read(JSON.writeValueAsString(new LinkedHashMap<String, Object>()));

        assertNull(sample.templateId());
        assertNull(sample.line());
        assertNull(sample.angle());
        assertEquals(MetricInsightAnalyzer.UNKNOWN, MetricInsightAnalyzer.bucketFor("template", sample));
        assertEquals(MetricInsightAnalyzer.UNKNOWN, MetricInsightAnalyzer.bucketFor("line", sample));
        assertEquals(MetricInsightAnalyzer.UNKNOWN, MetricInsightAnalyzer.bucketFor("angle", sample));
    }

    /**
     * 用生产真实的节点构造器造一份冻结输入。
     *
     * @param selection 线内编辑的选择
     * @return 冻结输入的根节点
     */
    private static Map<String, Object> frozenInput(EditorialSelection selection) {
        HeronNewsWork work = new HeronNewsWork();
        work.setCreateTime(LocalDateTime.of(2026, 11, 1, 9, 0));
        EditorialDecisionService.Decision decision = new EditorialDecisionService.Decision(
            "PUBLISH", selection.line(), "走数据看板线", "用图表讲清增速", 42L, 7L);
        ProductionSubmitBo command = new ProductionSubmitBo(
            ProductionBizType.news_explainer, 1L, "req-1", 1L, decision, selection);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", NewsProductionDomain.kindOf(selection));
        snapshot.put("editorial", NewsProductionDomain.editorialNode(work, command));
        snapshot.put("selection", NewsProductionDomain.selectionNode(selection));
        return snapshot;
    }

    /**
     * 一条从数据机会来的数据看板线选择：信号用真实的信号生产者生成。
     *
     * @return 线内编辑的选择
     */
    private static EditorialSelection dataBoardSelection() {
        DatasetSignal.Signal signal = new DatasetSignal.Signal("time_series", "全球", "gdp",
            "2026-01-01", "2026-10-01", 30, 30, 2.4, 12.5, 2.1, false, 1.0,
            "zscore_outlier", 3.2, 1.6, List.of());
        String signals = DataOpportunityService.signalsJson(signal, List.of());
        EditorialSelection.Opportunity opportunity = new EditorialSelection.Opportunity(
            7L, SOURCE_KEY, "全球 GDP 增速反超", signals, 30, "sha-abc", "time_series", "monthly", "gdp");
        return new EditorialSelection(EditorialSelection.DATA_BOARD, 42L, SOURCE_KEY, false,
            TEMPLATE_ID, "rank_competition", List.of("ev-1"), 7L, opportunity);
    }

    /**
     * 把冻结输入喂给归因服务，取出一条样本。
     *
     * @param input 冻结输入 JSON
     * @return 样本
     */
    private static MetricInsightAnalyzer.Sample read(String input) {
        HeronNewsMetricMapper.PerformanceSample row = new HeronNewsMetricMapper.PerformanceSample(
            1001L, 5000L, 0.42, 30L, 5L, 2L,
            LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 1, 20, 0), 62000,
            input, "{\"chart\":\"bar\"}", null, null, 42L);
        // 风格维度要从模板目录读 template.json：这里给一份空配置，读不到就归「未记录」。
        return new NewsMetricServiceImpl(null, null, new HeronProductionProperties()).toSample(row);
    }
}
