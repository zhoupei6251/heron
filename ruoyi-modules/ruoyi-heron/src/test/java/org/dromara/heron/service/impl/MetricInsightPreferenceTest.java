package org.dromara.heron.service.impl;

import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表现回流：只有证据够格的分组才允许影响选择。
 *
 * <p>三条最容易做坏的地方——三个样本就调权重、把「未记录」当一套版式、把表现差的分组拉黑——
 * 逐条钉住。
 */
@DisplayName("表现回流：偏好只做加权")
class MetricInsightPreferenceTest {

    private static final LocalDateTime WEEK_1 = LocalDateTime.of(2026, 11, 2, 20, 0);
    private static final LocalDateTime WEEK_2 = LocalDateTime.of(2026, 11, 9, 20, 0);

    private static MetricInsightAnalyzer.Sample sample(long id, String templateId, long plays,
                                                      LocalDateTime publishAt) {
        return new MetricInsightAnalyzer.Sample(id, plays, 0.32d, plays / 10, publishAt, publishAt, 60_000,
            templateId, "explainer", "angle", "article", "bar", null, null, null, null, null, null, null, null);
    }

    /** 一个可信分组：30 条样本、跨越两周，够到 L4。 */
    private static List<MetricInsightAnalyzer.Sample> group(String templateId, long plays, int samples) {
        List<MetricInsightAnalyzer.Sample> out = new ArrayList<>();
        for (int index = 0; index < samples; index++) {
            out.add(sample(index + 1L, templateId, plays + index,
                index % 2 == 0 ? WEEK_1.plusDays(index % 7) : WEEK_2.plusDays(index % 7)));
        }
        return out;
    }

    @Test
    @DisplayName("样本不足：一条都不进偏好，选择结果与不带偏好完全一致")
    void insufficientSamplesStayNeutral() {
        List<MetricInsightAnalyzer.Sample> samples = new ArrayList<>(group("good", 400, 7));
        samples.addAll(group("bad", 60, 7));

        MetricInsightAnalyzer.Preference preference = MetricInsightAnalyzer.preference("template", samples);

        assertFalse(preference.hasSignal(), "7 条样本连聚合值都没有，不能影响选择");
        assertEquals(1d, preference.weightOf("good"));
        assertEquals(1d, preference.weightOf("bad"));
    }

    @Test
    @DisplayName("证据到 L4：表现显著好的抬高、显著差的降权，但不拉黑")
    void significantWinnerIsWeightedUp() {
        List<MetricInsightAnalyzer.Sample> samples = new ArrayList<>(group("good", 400, 30));
        samples.addAll(group("bad", 60, 30));

        MetricInsightAnalyzer.Preference preference = MetricInsightAnalyzer.preference("template", samples);

        assertTrue(preference.hasSignal(), "两组都到 L4，应该给出权重");
        assertTrue(preference.weightOf("good") > 1.2d, "表现好的那套要被多抽到");
        assertTrue(preference.weightOf("bad") < 1d, "表现差的那套要被少抽到");
        assertTrue(preference.weightOf("bad") >= MetricInsightAnalyzer.WEIGHT_FLOOR,
            "降权不是拉黑：权重不能降到 0");
        assertTrue(preference.weightOf("good") <= MetricInsightAnalyzer.WEIGHT_CEIL);
    }

    @Test
    @DisplayName("未记录的分组不进偏好：它对应不到任何具体模板")
    void unknownBucketNeverWeighted() {
        List<MetricInsightAnalyzer.Sample> samples = new ArrayList<>(group("good", 400, 30));
        samples.addAll(group("mid", 120, 30));
        samples.addAll(group(null, 30, 30));

        MetricInsightAnalyzer.Preference preference = MetricInsightAnalyzer.preference("template", samples);

        assertTrue(preference.hasSignal(), "两个可信分组已经够比较");
        assertFalse(preference.weights().containsKey(MetricInsightAnalyzer.UNKNOWN));
        assertEquals(1d, preference.weightOf(MetricInsightAnalyzer.UNKNOWN));
    }

    @Test
    @DisplayName("只有一个可信分组时保持中性：没有可比对象，抬高它就是拿一条规律去赌")
    void singleUsableBucketStaysNeutral() {
        List<MetricInsightAnalyzer.Sample> samples = new ArrayList<>(group("good", 400, 30));
        samples.addAll(group("bad", 60, 3));

        MetricInsightAnalyzer.Preference preference = MetricInsightAnalyzer.preference("template", samples);

        assertFalse(preference.hasSignal());
    }

    @Test
    @DisplayName("分组结论不稳（去掉最高一条方向就变）时，权重保持中性")
    void unstableBucketIsNeutral() {
        NewsPerformanceInsightVo insight = new NewsPerformanceInsightVo();
        insight.setDimension("template");
        insight.setTotalSamples(60);
        insight.setTier("L4");
        insight.setBuckets(List.of(bucket("good", 400, 30, false), bucket("bad", 60, 30, true)));

        MetricInsightAnalyzer.Preference preference = MetricInsightAnalyzer.preferenceOf(insight);

        assertFalse(preference.weights().containsKey("good"), "不稳的分组不许进偏好");
        assertEquals(1d, preference.weightOf("good"));
    }

    @Test
    @DisplayName("带权抽样：固定种子下，表现好的那套被抽中的概率确实上升")
    void weightedPickFavoursTheWinner() {
        Map<String, Double> weights = new LinkedHashMap<>(Map.of("good", 1.43d, "bad", 0.57d));
        List<String> candidates = List.of("good", "bad");
        Random random = new Random(20261125L);
        int good = 0;
        for (int run = 0; run < 100; run++) {
            String picked = NewsTemplatePicker.weightedPick(candidates, weights, random);
            assertNotNull(picked, "100 次抽样都要落回候选里");
            assertTrue(candidates.contains(picked));
            if ("good".equals(picked)) {
                good++;
            }
        }

        assertTrue(good > 55, "表现好的那套要明显更常被选中，实际 " + good + " 次");
    }

    @Test
    @DisplayName("没有权重信号时抽样退化成等概率，候选集合不变")
    void neutralWeightsStayEqual() {
        List<String> candidates = List.of("a", "b", "c");
        Random random = new Random(7L);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int run = 0; run < 300; run++) {
            String picked = NewsTemplatePicker.weightedPick(candidates, Map.of(), random);
            assertTrue(candidates.contains(picked), "只能从候选里抽");
            counts.merge(picked, 1, Integer::sum);
        }

        assertEquals(3, counts.size(), "三套候选都应该被抽到过");
        assertTrue(counts.values().stream().allMatch(count -> count > 60), counts.toString());
    }

    private static NewsPerformanceInsightVo.Bucket bucket(String name, double medianPlays,
                                                          int samples, boolean eligible) {
        NewsPerformanceInsightVo.Bucket bucket = new NewsPerformanceInsightVo.Bucket();
        bucket.setBucket(name);
        bucket.setSamples(samples);
        bucket.setMedianPlays(medianPlays);
        bucket.setStrategyEligible(eligible);
        bucket.setStable(eligible);
        bucket.setTier(eligible ? "L4" : "L3");
        return bucket;
    }
}
