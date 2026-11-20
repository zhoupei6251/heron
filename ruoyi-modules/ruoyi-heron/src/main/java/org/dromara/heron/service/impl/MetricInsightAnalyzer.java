package org.dromara.heron.service.impl;

import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;

import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表现归因的纯计算层：分桶 + 中位数 + 样本等级 + 稳定性。
 *
 * <p>这里不碰数据库也不碰模型，所有结论都能被指回样本行本身。三条硬规矩：
 * 一是取不到的属性一律进「未记录」，不猜；二是用中位数不用均值，一条爆款会把均值抬走；
 * 三是样本不够就说样本不够，不把观察说成规律。
 */
public final class MetricInsightAnalyzer {

    /** 可用维度白名单。每个维度都必须能从冻结输入或版本行里直接读出来。 */
    public static final List<String> DIMENSIONS = List.of(
        "template", "line", "angle", "source", "column", "kind", "chart", "duration", "weekday", "signal",
        "style", "voice", "publishHour", "hook", "opening");

    /** 取不到的属性值统一归到这里，页面能看出覆盖率缺口。 */
    public static final String UNKNOWN = "未记录";

    /** 桶里保留的原始样本条数上限。再多对页面没有意义，只会拖慢响应。 */
    static final int RAW_SAMPLE_LIMIT = 20;

    private static final List<String> WEEKDAYS =
        List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");

    private MetricInsightAnalyzer() {
    }

    /** 一条视频的一次表现采样，连同它身上能直接读出来的创作属性。 */
    public record Sample(
        long versionId,
        Long plays,
        Double completionRate,
        Long likes,
        LocalDateTime publishAt,
        LocalDateTime fetchedAt,
        Integer durationMs,
        String templateId,
        String line,
        String angle,
        String kind,
        String chart,
        String dataSourceKey,
        String sourceKind,
        Long candidateId,
        String signalType,
        /** 模板的视觉风格键；由模板目录的 template.json 读出，取不到即空。 */
        String styleKey,
        /** 配音音色；来自冻结快照的 assets.voiceKey。 */
        String voiceKey,
        /** 标题钩子类型；来自生产期落下的 content_profile。 */
        String hookType,
        /** 开头结构；来自生产期落下的 content_profile。 */
        String openingShape
    ) {
        /** 归属日期：发布过就按发布时间算周，没发布就按采样时间算。 */
        public LocalDateTime date() {
            return publishAt != null ? publishAt : fetchedAt;
        }
    }

    /** 这个样本在指定维度上归到哪一格。 */
    public static String bucketFor(String dimension, Sample sample) {
        return switch (dimension) {
            case "template" -> blankToUnknown(sample.templateId());
            case "line" -> blankToUnknown(sample.line());
            case "angle" -> blankToUnknown(sample.angle());
            case "source" -> sourceBucket(sample);
            case "column" -> sample.dataSourceKey() == null || sample.dataSourceKey().isBlank()
                ? UNKNOWN : Column.forSource(sample.dataSourceKey()).displayName();
            case "kind" -> blankToUnknown(sample.kind());
            case "chart" -> blankToUnknown(sample.chart());
            case "duration" -> durationBucket(sample.durationMs());
            case "weekday" -> sample.publishAt() == null ? UNKNOWN : WEEKDAYS.get(sample.publishAt().getDayOfWeek().getValue() - 1);
            case "signal" -> blankToUnknown(sample.signalType());
            case "style" -> blankToUnknown(sample.styleKey());
            case "voice" -> blankToUnknown(sample.voiceKey());
            case "publishHour" -> publishHourBucket(sample);
            case "hook" -> blankToUnknown(sample.hookType());
            case "opening" -> blankToUnknown(sample.openingShape());
            default -> throw new IllegalArgumentException("未知维度：" + dimension);
        };
    }

    /**
     * 发布时间段分箱。
     *
     * <p>只做相关归因，不表达因果：发布时间对表现的影响是众所周知的，
     * 但这条维度能说明的只是「已有样本里这个时段的作品表现如何」。
     */
    static String publishHourBucket(Sample sample) {
        LocalDateTime when = sample.publishAt() != null ? sample.publishAt() : sample.fetchedAt();
        if (when == null) return UNKNOWN;
        int hour = when.getHour();
        if (hour < 6) return "凌晨";
        if (hour < 12) return "上午";
        if (hour < 14) return "午间";
        if (hour < 18) return "下午";
        return "晚间";
    }

    /**
     * 来源维度：数据看板线、研究来源、热榜、没记录，四者分开。
     *
     * <p>只看候选的来源列会把每条数据看板视频都算成热榜——占位候选没有来源，
     * 但它有 candidate_id，旧口径于是把它判成 hotlist。先看子线，再看来源。
     */
    private static String sourceBucket(Sample sample) {
        if ("trend".equals(sample.kind())) return "trend";
        if ("data_board".equals(sample.kind())) return "data_board";
        if ("research".equals(sample.sourceKind())) return "research";
        if (sample.candidateId() == null) return UNKNOWN;
        return "hotlist";
    }

    private static String durationBucket(Integer durationMs) {
        if (durationMs == null) return UNKNOWN;
        int seconds = durationMs / 1000;
        if (seconds <= 30) return "≤30秒";
        if (seconds <= 60) return "30–60秒";
        if (seconds <= 90) return "60–90秒";
        if (seconds <= 120) return "90–120秒";
        return ">120秒";
    }

    /**
     * 把样本按维度分桶并算出每桶的结论。
     *
     * @param dimension 维度键，必须在 {@link #DIMENSIONS} 里
     * @param samples  全部样本
     */
    public static NewsPerformanceInsightVo analyze(String dimension, List<Sample> samples) {
        Map<String, List<Sample>> grouped = new LinkedHashMap<>();
        for (Sample sample : samples) {
            grouped.computeIfAbsent(bucketFor(dimension, sample), key -> new ArrayList<>()).add(sample);
        }
        List<Double> allPlays = samples.stream()
            .map(Sample::plays).filter(java.util.Objects::nonNull).map(Double::valueOf).toList();

        List<NewsPerformanceInsightVo.Bucket> buckets = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> entry : grouped.entrySet()) {
            buckets.add(summarize(entry.getKey(), entry.getValue(), allPlays));
        }
        buckets.sort((left, right) -> {
            int rank = Integer.compare(tierRank(right.getTier()), tierRank(left.getTier()));
            if (rank != 0) return rank;
            int size = Integer.compare(right.getSamples(), left.getSamples());
            if (size != 0) return size;
            return Double.compare(nullToZero(right.getMedianPlays()), nullToZero(left.getMedianPlays()));
        });

        NewsPerformanceInsightVo vo = new NewsPerformanceInsightVo();
        vo.setDimension(dimension);
        vo.setTotalSamples(samples.size());
        vo.setWeeks(weekCount(samples));
        vo.setTier(tier(vo.getTotalSamples(), vo.getWeeks()));
        vo.setConclusion(conclusion(vo.getTier()));
        vo.setBuckets(buckets);
        vo.setRawSamples(rawSamples(samples));
        vo.setNotice(notice(vo.getTier()));
        return vo;
    }

    private static NewsPerformanceInsightVo.Bucket summarize(String bucket, List<Sample> group,
                                                              List<Double> allPlays) {
        List<Double> plays = group.stream()
            .map(Sample::plays).filter(java.util.Objects::nonNull).map(Double::valueOf).toList();
        List<Double> completion = group.stream()
            .map(Sample::completionRate).filter(java.util.Objects::nonNull).toList();
        List<Double> likes = group.stream()
            .map(Sample::likes).filter(java.util.Objects::nonNull).map(Double::valueOf).toList();

        double medianPlays = median(plays);
        double p25 = quantile(plays, 0.25);
        double p75 = quantile(plays, 0.75);
        Long maxPlays = plays.isEmpty() ? null : Math.round(Collections.max(plays));
        Double outlierRatio = maxPlays == null || medianPlays <= 0 ? null : maxPlays / medianPlays;
        int weeks = weekCount(group);
        boolean stable = stable(plays, subtract(allPlays, plays));

        String tier = tier(group.size(), weeks);
        if (!stable) tier = downgrade(tier);

        NewsPerformanceInsightVo.Bucket vo = new NewsPerformanceInsightVo.Bucket();
        vo.setBucket(bucket);
        vo.setSamples(group.size());
        vo.setWeeks(weeks);
        vo.setTier(tier);
        vo.setConclusion(conclusion(tier));
        vo.setStable(stable);
        vo.setStrategyEligible("L4".equals(tier) && stable);
        vo.setRawSamples(rawSamples(group));
        vo.setRawSamplesTruncated(group.size() > RAW_SAMPLE_LIMIT);
        vo.setExtremeSample(extremeSample(group));
        // 样本不够就不给聚合值：L0/L1 给一个中位数，读的人会把它当结论用。
        // 原始样本和条数照样给——那才是「不足时该看的东西」。
        if (aggregatable(tier)) {
            vo.setMedianPlays(round(medianPlays));
            vo.setP25Plays(round(p25));
            vo.setP75Plays(round(p75));
            vo.setAvgPlays(round(average(plays)));
            vo.setMaxPlays(maxPlays);
            vo.setMedianCompletionRate(round(median(completion)));
            vo.setMedianLikes(round(median(likes)));
            vo.setOutlierRatio(round(outlierRatio));
        }
        vo.setSummary(summary(bucket, group.size(), weeks, tier,
            aggregatable(tier) ? medianPlays : null, aggregatable(tier) ? maxPlays : null,
            aggregatable(tier) ? outlierRatio : null, aggregatable(tier) ? median(completion) : null,
            stable));
        return vo;
    }

    /** 只有 L2 起才给聚合统计量；L0/L1 只给条数与原始样本。 */
    static boolean aggregatable(String tier) {
        return "L2".equals(tier) || "L3".equals(tier) || "L4".equals(tier);
    }

    /** 原始样本明细。条数上限固定，超出只保留前若干条并标出已截断。 */
    static List<NewsPerformanceInsightVo.RawSample> rawSamples(List<Sample> samples) {
        List<NewsPerformanceInsightVo.RawSample> out = new ArrayList<>();
        for (Sample sample : samples) {
            if (out.size() >= RAW_SAMPLE_LIMIT) {
                break;
            }
            NewsPerformanceInsightVo.RawSample raw = new NewsPerformanceInsightVo.RawSample();
            raw.setVersionId(sample.versionId());
            raw.setPlays(sample.plays());
            raw.setCompletionRate(sample.completionRate());
            raw.setLikes(sample.likes());
            raw.setFetchedAt(sample.fetchedAt());
            raw.setPublishAt(sample.publishAt());
            out.add(raw);
        }
        return out;
    }

    /**
     * 离群样本：这一组播放最高的那条。
     *
     * <p>它不删——删掉爆款会掩盖「这类内容能爆」这个事实，那是选题情报不是噪声。
     * 单独列出来，是让「这一组被单条撑起来」看得见。
     */
    static NewsPerformanceInsightVo.RawSample extremeSample(List<Sample> group) {
        Sample top = null;
        for (Sample sample : group) {
            if (sample.plays() == null) {
                continue;
            }
            if (top == null || sample.plays() > top.plays()) {
                top = sample;
            }
        }
        if (top == null) {
            return null;
        }
        NewsPerformanceInsightVo.RawSample raw = new NewsPerformanceInsightVo.RawSample();
        raw.setVersionId(top.versionId());
        raw.setPlays(top.plays());
        raw.setCompletionRate(top.completionRate());
        raw.setLikes(top.likes());
        raw.setFetchedAt(top.fetchedAt());
        raw.setPublishAt(top.publishAt());
        return raw;
    }

    /**
     * 留一稳定性：去掉这一组最高的那条，结论方向会不会翻。
     *
     * <p>方向是「这一组的中位数比其余样本高还是低」。参照物必须是不含本组的其余样本：
     * 拿本组跟「含本组的总中位数」比，样本占多数时是自己跟自己比，方向是假的。
     *
     * <p>翻了就说明这条结论是被单条撑起来的，不能当规律。样本少于 3 条、
     * 或没有别的样本可比时，谈不上被单条支配，直接算稳定。
     */
    static boolean stable(List<Double> bucketPlays, List<Double> restPlays) {
        if (bucketPlays.size() < 3 || restPlays.isEmpty()) return true;
        int direction = Integer.signum(Double.compare(median(bucketPlays), median(restPlays)));
        if (direction == 0) return true;
        double max = Collections.max(bucketPlays);
        List<Double> trimmed = new ArrayList<>(bucketPlays);
        trimmed.remove(max);
        int trimmedDirection = Integer.signum(Double.compare(median(trimmed), median(restPlays)));
        return trimmedDirection == direction;
    }

    /** 从全集里减掉一组的样本，剩下的就是对照组。值相同减哪一条都一样。 */
    private static List<Double> subtract(List<Double> all, List<Double> part) {
        List<Double> rest = new ArrayList<>(all);
        for (Double value : part) {
            rest.remove(value);
        }
        return rest;
    }

    /** 样本等级：量不够就降级，覆盖周期太短也不能算结论。 */
    public static String tier(int samples, int weeks) {
        if (samples <= 0) return "L0";
        if (samples < 3) return "L1";
        if (samples < 10) return "L2";
        if (samples < 30) return "L3";
        return weeks >= 2 ? "L4" : "L3";
    }

    private static int tierRank(String tier) {
        return switch (tier) {
            case "L4" -> 4;
            case "L3" -> 3;
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }

    /** 结论类型：等级决定结论能说到什么程度。 */
    public static String conclusion(String tier) {
        return switch (tier) {
            case "L4" -> "evidence_conclusion";
            case "L3" -> "strategy_hypothesis";
            case "L2", "L1" -> "insufficient_sample";
            default -> "unvalidated_suggestion";
        };
    }

    private static String tierMeaning(String tier) {
        return switch (tier) {
            case "L4" -> "可以当结论用";
            case "L3" -> "只够作为待人工确认的策略假设";
            case "L2", "L1" -> "样本不足，只能当观察";
            default -> "没有样本，任何说法都只是未验证的建议";
        };
    }

    private static String notice(String tier) {
        return "数字全部来自人工回填的发布表现，每版只取最新一次采样。"
            + "当前证据等级 " + tier + "：" + tierMeaning(tier)
            + "。样本不足或被单条极端值主导时，不要把它当成规律去改生产策略。";
    }

    private static String summary(String bucket, int samples, int weeks, String tier,
                                  Double medianPlays, Long maxPlays, Double outlierRatio,
                                  Double medianCompletion, boolean stable) {
        StringBuilder text = new StringBuilder();
        text.append(bucket).append("：").append(samples).append(" 条样本");
        if (weeks > 0) text.append("，覆盖 ").append(weeks).append(" 周");
        if (medianPlays == null) {
            text.append("；样本不足，只给原始样本，不给中位数");
        } else {
            text.append("；播放中位数 ").append(round(medianPlays));
            if (maxPlays != null) text.append("，最高 ").append(maxPlays);
            if (outlierRatio != null && outlierRatio > 5) {
                text.append("（最高是中位数的 ").append(round(outlierRatio))
                    .append(" 倍，这一组被单条撑起来的可能性大）");
            }
            if (medianCompletion != null && medianCompletion > 0) {
                text.append("；完播中位数 ").append(round(medianCompletion * 100)).append("%");
            }
        }
        if (!stable) text.append("；去掉最高一条方向就翻了，不稳定");
        text.append("；证据等级 ").append(tier).append("，").append(tierMeaning(tier));
        return text.toString();
    }

    /** 样本覆盖了几个自然周。 */
    static int weekCount(List<Sample> samples) {
        return (int) samples.stream()
            .map(Sample::date)
            .filter(java.util.Objects::nonNull)
            .map(date -> date.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)))
            .distinct()
            .count();
    }

    /** 中位数（p50）。空集合返回 NaN，调用方自行判断。 */
    public static double median(List<Double> values) {
        return quantile(values, 0.5);
    }

    /** 线性插值分位数。 */
    public static double quantile(List<Double> values, double q) {
        if (values == null || values.isEmpty()) return Double.NaN;
        List<Double> sorted = values.stream()
            .filter(java.util.Objects::nonNull).filter(v -> !v.isNaN()).sorted().toList();
        if (sorted.isEmpty()) return Double.NaN;
        if (sorted.size() == 1) return sorted.get(0);
        double position = q * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        return sorted.get(lower) + (position - lower) * (sorted.get(upper) - sorted.get(lower));
    }

    private static double average(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /** 不稳定就降一级，并标出来。 */
    private static String downgrade(String tier) {
        return switch (tier) {
            case "L4" -> "L3";
            case "L3" -> "L2";
            case "L2" -> "L1";
            default -> tier;
        };
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.strip();
    }

    private static double nullToZero(Double value) {
        return value == null ? 0d : value;
    }

    private static Double round(double value) {
        return Double.isNaN(value) ? null : Math.round(value * 100d) / 100d;
    }
}
