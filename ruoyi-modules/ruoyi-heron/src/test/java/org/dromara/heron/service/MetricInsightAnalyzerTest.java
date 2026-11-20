package org.dromara.heron.service;

import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.service.impl.MetricInsightAnalyzer;
import org.dromara.heron.service.impl.MetricInsightAnalyzer.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表现归因的纯计算层：分桶口径、样本等级、稳定性。
 *
 * <p>这些结论会直接被当成「哪类内容更受欢迎」的依据，所以口径必须先被钉死：
 * 取不到就是未记录、爆款不能抬走中位数、单条撑起来的方向不算方向。
 */
@DisplayName("表现归因：分桶口径与样本等级")
class MetricInsightAnalyzerTest {

    @Test
    @DisplayName("数据看板的视频不能再被算成热榜")
    void dataBoardIsNotHotlist() {
        Sample sample = sample().kind("data_board").sourceKind(null).candidateId(9L).build();

        assertEquals("data_board", MetricInsightAnalyzer.bucketFor("source", sample),
            "占位候选没有来源列，但它是数据看板线，不能算热榜");
    }

    @Test
    @DisplayName("来源维度四种取值分开：数据看板 / 研究 / 热榜 / 未记录")
    void sourceBucketsAreSeparated() {
        assertEquals("data_board",
            MetricInsightAnalyzer.bucketFor("source", sample().kind("data_board").build()));
        assertEquals("research",
            MetricInsightAnalyzer.bucketFor("source", sample().kind(null).sourceKind("research").build()));
        assertEquals("hotlist",
            MetricInsightAnalyzer.bucketFor("source", sample().kind(null).sourceKind(null).candidateId(7L).build()));
        assertEquals("未记录",
            MetricInsightAnalyzer.bucketFor("source", sample().kind(null).sourceKind(null).candidateId(null).build()));
    }

    @Test
    @DisplayName("取不到的属性一律归入未记录，不猜")
    void missingAttributesFallBack() {
        Sample sample = sample().templateId(null).line(null).angle(null).chart(null)
            .dataSourceKey(null).signalType(null).build();

        for (String dimension : List.of("template", "line", "angle", "chart", "column", "signal")) {
            assertEquals("未记录", MetricInsightAnalyzer.bucketFor(dimension, sample),
                dimension + " 不该被猜出来");
        }
    }

    @Test
    @DisplayName("栏目由数据源推出")
    void columnComesFromSourceKey() {
        assertEquals("全球市场",
            MetricInsightAnalyzer.bucketFor("column", sample().dataSourceKey("sina-gold").build()));
        assertEquals("全球数据图鉴",
            MetricInsightAnalyzer.bucketFor("column", sample().dataSourceKey("worldbank-gdp").build()));
    }

    @Test
    @DisplayName("时长分档")
    void durationBuckets() {
        assertEquals("≤30秒", MetricInsightAnalyzer.bucketFor("duration", sample().durationMs(28_000).build()));
        assertEquals("30–60秒", MetricInsightAnalyzer.bucketFor("duration", sample().durationMs(45_000).build()));
        assertEquals(">120秒", MetricInsightAnalyzer.bucketFor("duration", sample().durationMs(180_000).build()));
        assertEquals("未记录", MetricInsightAnalyzer.bucketFor("duration", sample().durationMs(null).build()));
    }

    @Test
    @DisplayName("中位数不被爆款抬走")
    void medianSurvivesOutlier() {
        assertEquals(100.0, MetricInsightAnalyzer.median(List.of(10.0, 100.0, 1000.0)));
        assertEquals(55.0, MetricInsightAnalyzer.median(List.of(10.0, 100.0)), 0.001);
        assertTrue(Double.isNaN(MetricInsightAnalyzer.median(List.of())));
    }

    @Test
    @DisplayName("样本等级按量与覆盖周数走")
    void tierLadder() {
        assertEquals("L0", MetricInsightAnalyzer.tier(0, 0));
        assertEquals("L1", MetricInsightAnalyzer.tier(2, 1));
        assertEquals("L2", MetricInsightAnalyzer.tier(3, 1));
        assertEquals("L3", MetricInsightAnalyzer.tier(29, 4));
        assertEquals("L4", MetricInsightAnalyzer.tier(30, 2));
        assertEquals("L3", MetricInsightAnalyzer.tier(30, 1), "只覆盖一周不算能下结论");
    }

    @Test
    @DisplayName("结论类型跟着等级走：结论 / 假设 / 观察 / 未验证建议")
    void conclusionFollowsTier() {
        assertEquals("evidence_conclusion", MetricInsightAnalyzer.conclusion("L4"));
        assertEquals("strategy_hypothesis", MetricInsightAnalyzer.conclusion("L3"));
        assertEquals("insufficient_sample", MetricInsightAnalyzer.conclusion("L2"));
        assertEquals("insufficient_sample", MetricInsightAnalyzer.conclusion("L1"));
        assertEquals("unvalidated_suggestion", MetricInsightAnalyzer.conclusion("L0"));
    }

    @Test
    @DisplayName("被单条爆款撑起来的组要标出异常倍数")
    void outlierBucketIsFlagged() {
        List<Sample> samples = List.of(
            sample().line("甲").plays(10L).date("2026-11-02").build(),
            sample().line("甲").plays(10L).date("2026-11-03").build(),
            sample().line("甲").plays(1000L).date("2026-11-04").build(),
            sample().line("乙").plays(90L).date("2026-11-02").build(),
            sample().line("乙").plays(90L).date("2026-11-03").build(),
            sample().line("乙").plays(90L).date("2026-11-04").build());

        NewsPerformanceInsightVo.Bucket bucket = MetricInsightAnalyzer.analyze("line", samples)
            .getBuckets().stream().filter(b -> "甲".equals(b.getBucket())).findFirst().orElseThrow();

        assertEquals(10.0, bucket.getMedianPlays(), 0.01, "中位数不能被那条爆款抬走");
        assertEquals(1000L, bucket.getMaxPlays());
        assertTrue(bucket.getOutlierRatio() > 5, "最高值与中位数差太远必须被标出来");
        assertTrue(bucket.getSummary().contains("倍"), "结论里要写明被单条撑起来的风险");
    }

    @Test
    @DisplayName("去掉最高那条方向就翻的组不算稳定，等级降一级")
    void bucketProppedUpByOneSampleIsNotStable() {
        List<Sample> samples = List.of(
            sample().line("甲").plays(1L).date("2026-11-02").build(),
            sample().line("甲").plays(100L).date("2026-11-03").build(),
            sample().line("甲").plays(100L).date("2026-11-04").build(),
            sample().line("乙").plays(90L).date("2026-11-02").build(),
            sample().line("乙").plays(90L).date("2026-11-03").build(),
            sample().line("乙").plays(90L).date("2026-11-04").build());

        NewsPerformanceInsightVo.Bucket bucket = MetricInsightAnalyzer.analyze("line", samples)
            .getBuckets().stream().filter(b -> "甲".equals(b.getBucket())).findFirst().orElseThrow();

        assertFalse(bucket.isStable(), "去掉最高那条，甲就不再比乙高，方向是撑出来的");
        assertEquals("L1", bucket.getTier(), "不稳定要降一级，不能按原来的等级说");
        assertFalse(bucket.isStrategyEligible());
    }

    @Test
    @DisplayName("样本够、方向稳、跨周的组才可能进策略")
    void stableBucketIsEligibleWhenTierIsL4() {
        List<Sample> samples = new ArrayList<>();
        for (int day = 1; day <= 4; day++) {
            for (int index = 0; index < 10; index++) {
                samples.add(sample().line("甲").plays(1000L + index * 10L)
                    .date("2026-11-0" + day).build());
            }
        }
        samples.add(sample().line("乙").plays(10L).date("2026-11-01").build());

        NewsPerformanceInsightVo insight = MetricInsightAnalyzer.analyze("line", samples);
        NewsPerformanceInsightVo.Bucket bucket = insight.getBuckets().stream()
            .filter(b -> "甲".equals(b.getBucket())).findFirst().orElseThrow();

        assertEquals("L4", bucket.getTier());
        assertEquals("evidence_conclusion", bucket.getConclusion());
        assertTrue(bucket.isStable());
        assertTrue(bucket.isStrategyEligible());
        assertEquals(40, bucket.getSamples());
        assertEquals(2, bucket.getWeeks(), "11-01 是上一周，11-02 起是下一周");
    }

    @Test
    @DisplayName("未知维度直接拒绝")
    void unknownDimensionRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> MetricInsightAnalyzer.bucketFor("plays; drop table", sample().build()));
    }

    @Test
    @DisplayName("样本不足的分组不给聚合值，只给原始样本")
    void insufficientBucketSuppressesAggregates() {
        List<Sample> samples = List.of(
            sample().line("甲").plays(100L).date("2026-11-02").build(),
            sample().line("甲").plays(120L).date("2026-11-03").build());

        NewsPerformanceInsightVo.Bucket bucket = MetricInsightAnalyzer.analyze("line", samples)
            .getBuckets().stream().filter(b -> "甲".equals(b.getBucket())).findFirst().orElseThrow();

        assertEquals("L1", bucket.getTier());
        assertNull(bucket.getMedianPlays(), "样本不足时给中位数，读的人会把它当结论用");
        assertNull(bucket.getP25Plays());
        assertNull(bucket.getP75Plays());
        assertNull(bucket.getMaxPlays());
        assertNull(bucket.getOutlierRatio());
        assertEquals(2, bucket.getRawSamples().size(), "原始样本照样要给");
        assertTrue(bucket.getSummary().contains("样本不足"), bucket.getSummary());
    }

    @Test
    @DisplayName("离群样本不删，单独列出来")
    void extremeSampleIsListedNotRemoved() {
        List<Sample> samples = List.of(
            sample().line("甲").plays(100L).date("2026-11-02").build(),
            sample().line("甲").plays(120L).date("2026-11-03").build(),
            sample().line("甲").plays(9000L).date("2026-11-04").build());

        NewsPerformanceInsightVo.Bucket bucket = MetricInsightAnalyzer.analyze("line", samples)
            .getBuckets().stream().filter(b -> "甲".equals(b.getBucket())).findFirst().orElseThrow();

        assertEquals(3, bucket.getSamples(), "离群样本仍算在样本量里");
        assertNotNull(bucket.getExtremeSample());
        assertEquals(9000L, bucket.getExtremeSample().getPlays());
    }

    @Test
    @DisplayName("原始样本条数有上限，超出要标出已截断")
    void rawSamplesAreTruncatedWithFlag() {
        List<Sample> samples = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            samples.add(sample().line("甲").plays(100L).date("2026-11-0" + (index % 9 + 1)).build());
        }

        NewsPerformanceInsightVo.Bucket bucket = MetricInsightAnalyzer.analyze("line", samples)
            .getBuckets().stream().filter(b -> "甲".equals(b.getBucket())).findFirst().orElseThrow();

        assertEquals(20, bucket.getRawSamples().size());
        assertTrue(bucket.isRawSamplesTruncated());
        assertEquals(25, bucket.getSamples(), "截断的是明细，不是样本量");
    }

    @Test
    @DisplayName("新增维度：风格、配音、发布时间段、钩子类型、开头结构")
    void newDimensionsBucket() {
        Sample sample = sample().styleKey("broadcast").voiceKey("zh-CN-XiaoxiaoNeural")
            .hookType("数字反差").openingShape("data_first").build();

        assertEquals("broadcast", MetricInsightAnalyzer.bucketFor("style", sample));
        assertEquals("zh-CN-XiaoxiaoNeural", MetricInsightAnalyzer.bucketFor("voice", sample));
        assertEquals("数字反差", MetricInsightAnalyzer.bucketFor("hook", sample));
        assertEquals("data_first", MetricInsightAnalyzer.bucketFor("opening", sample));
    }

    @Test
    @DisplayName("新增维度取不到时归未记录，不猜")
    void newDimensionsFallBackToUnknown() {
        Sample sample = sample().styleKey(null).voiceKey(null).hookType(null).openingShape(null).build();

        for (String dimension : List.of("style", "voice", "hook", "opening")) {
            assertEquals("未记录", MetricInsightAnalyzer.bucketFor(dimension, sample), dimension + " 不该被猜出来");
        }
    }

    @Test
    @DisplayName("发布时间段分箱")
    void publishHourBuckets() {
        assertEquals("凌晨", MetricInsightAnalyzer.bucketFor("publishHour", sampleAt(3).build()));
        assertEquals("上午", MetricInsightAnalyzer.bucketFor("publishHour", sampleAt(9).build()));
        assertEquals("午间", MetricInsightAnalyzer.bucketFor("publishHour", sampleAt(12).build()));
        assertEquals("下午", MetricInsightAnalyzer.bucketFor("publishHour", sampleAt(15).build()));
        assertEquals("晚间", MetricInsightAnalyzer.bucketFor("publishHour", sampleAt(20).build()));
    }

    @Test
    @DisplayName("所有维度都在白名单里，前端多一个少一个都会 400")
    void dimensionsCoverTheNewOnes() {
        for (String dimension : List.of("style", "voice", "publishHour", "hook", "opening")) {
            assertTrue(MetricInsightAnalyzer.DIMENSIONS.contains(dimension), dimension + " 必须在白名单里");
        }
    }

    @Test
    @DisplayName("没有任何样本时，整页结论只能是未验证的建议")
    void emptyInsightIsUnvalidated() {
        NewsPerformanceInsightVo insight = MetricInsightAnalyzer.analyze("template", List.of());

        assertEquals(0, insight.getTotalSamples());
        assertEquals("L0", insight.getTier());
        assertEquals("unvalidated_suggestion", insight.getConclusion());
        assertTrue(insight.getBuckets().isEmpty());
    }

    private static Builder sample() {
        return new Builder();
    }

    /** 指定发布时刻的样本：发布时间段分箱只看小时。 */
    private static Builder sampleAt(int hour) {
        return new Builder().date(LocalDateTime.of(2026, 11, 2, hour, 0));
    }

    /** 测试用样本构造器：默认给一组能算出确定分桶的属性。 */
    private static final class Builder {
        private Long plays = 100L;
        private Long likes = 10L;
        private Double completionRate = 0.3d;
        private LocalDateTime date = LocalDateTime.of(2026, 11, 2, 10, 0);
        private Integer durationMs = 45_000;
        private String templateId = "news-data-9x16";
        private String line = "DATA_BOARD";
        private String angle = "数字反差";
        private String kind = "data_board";
        private String chart = "bar";
        private String dataSourceKey = "sina-gold";
        private String sourceKind = null;
        private Long candidateId = 1L;
        private String signalType = "extreme";
        private String styleKey = null;
        private String voiceKey = null;
        private String hookType = null;
        private String openingShape = null;

        Builder plays(Long value) { this.plays = value; return this; }
        Builder date(String iso) { this.date = LocalDateTime.parse(iso + "T10:00:00"); return this; }
        Builder date(LocalDateTime value) { this.date = value; return this; }
        Builder durationMs(Integer value) { this.durationMs = value; return this; }
        Builder templateId(String value) { this.templateId = value; return this; }
        Builder line(String value) { this.line = value; return this; }
        Builder angle(String value) { this.angle = value; return this; }
        Builder kind(String value) { this.kind = value; return this; }
        Builder chart(String value) { this.chart = value; return this; }
        Builder dataSourceKey(String value) { this.dataSourceKey = value; return this; }
        Builder sourceKind(String value) { this.sourceKind = value; return this; }
        Builder candidateId(Long value) { this.candidateId = value; return this; }
        Builder signalType(String value) { this.signalType = value; return this; }
        Builder styleKey(String value) { this.styleKey = value; return this; }
        Builder voiceKey(String value) { this.voiceKey = value; return this; }
        Builder hookType(String value) { this.hookType = value; return this; }
        Builder openingShape(String value) { this.openingShape = value; return this; }

        Sample build() {
            return new Sample(1L, plays, completionRate, likes, date, date, durationMs,
                templateId, line, angle, kind, chart, dataSourceKey, sourceKind, candidateId, signalType,
                styleKey, voiceKey, hookType, openingShape);
        }
    }
}
