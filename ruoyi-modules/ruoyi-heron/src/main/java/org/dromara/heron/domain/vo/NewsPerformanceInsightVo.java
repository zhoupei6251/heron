package org.dromara.heron.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 发布表现的归因结论页。
 *
 * <p>这页的价值不在「哪个模板平均播放高」，而在把每条结论能说到什么程度标清楚：
 * 有证据的结论、样本不足的观察、待人工确认的策略假设、尚未验证的建议，四者不能混着说。
 * 所有数字都来自人工回填的采样行，可被逐条指回；没有任何推断出来的「规律」。
 */
@Data
public class NewsPerformanceInsightVo {

    /** 归因维度：template / line / angle / source / column / kind / chart / duration / weekday / signal。 */
    private String dimension;

    /** 参与统计的样本数（同一版本只取最新一次采样）。 */
    private int totalSamples;

    /** 全部样本覆盖的自然周数。 */
    private int weeks;

    /** 整体证据等级 L0~L4。 */
    private String tier;

    /** 整体结论类型，见 {@link Bucket#getConclusion()} 的取值。 */
    private String conclusion;

    /** 这一页的结论能说到什么程度，写给人看。 */
    private String notice;

    /** 各分组明细。 */
    private List<Bucket> buckets;

    /** 全部样本的原始明细；样本不足时前端靠它给出「有哪些数据」，而不是一个空表。 */
    private List<RawSample> rawSamples;

    /**
     * 一条样本的原始明细。
     *
     * <p>样本不足的时候，聚合值一律不给，只给这些行：让读的人自己看见「就这几条」。
     */
    @Data
    public static class RawSample {

        /** 版本号；可指回具体那条视频。 */
        private long versionId;

        /** 播放量。 */
        private Long plays;

        /** 完播率。 */
        private Double completionRate;

        /** 点赞数。 */
        private Long likes;

        /** 采样时间。 */
        private java.time.LocalDateTime fetchedAt;

        /** 发布时间；没发布过为空。 */
        private java.time.LocalDateTime publishAt;
    }

    /**
     * 一个分组的结论。
     *
     * <p>conclusion 取值：
     * evidence_conclusion 有证据的数据结论；
     * strategy_hypothesis 模型提出的内容策略假设（样本够看出相关，但还得人确认）；
     * insufficient_sample 样本不足的观察；
     * unvalidated_suggestion 尚未验证的创作建议。
     */
    @Data
    public static class Bucket {

        /** 分组值；该维度取不到时是「未记录」，不猜。 */
        private String bucket;

        /** 样本数。 */
        private int samples;

        /** 播放量中位数（p50）。用中位数不用均值：一条爆款会把均值抬走。 */
        private Double medianPlays;

        /** 播放量下四分位（p25）。 */
        private Double p25Plays;

        /** 播放量上四分位（p75）。 */
        private Double p75Plays;

        /** 播放量均值，仅作对照，不作为结论依据。 */
        private Double avgPlays;

        /** 最高播放量。 */
        private Long maxPlays;

        /** 完播率中位数。 */
        private Double medianCompletionRate;

        /** 点赞中位数。 */
        private Double medianLikes;

        /** 最高播放 / 中位播放；大于 5 说明这一组被单条极端值主导。 */
        private Double outlierRatio;

        /** 覆盖的自然周数。 */
        private int weeks;

        /** 证据等级 L0~L4。 */
        private String tier;

        /** 结论类型。 */
        private String conclusion;

        /** 一句话结论，写清依据和保留。 */
        private String summary;

        /** 去掉最高的一条后方向是否还成立；不成立说明这条结论不稳。 */
        private boolean stable;

        /** 是否可直接进入内容策略（只有 L4 且稳定才可以）。 */
        private boolean strategyEligible;

        /**
         * 这一桶的原始样本明细。
         *
         * <p>L1/L2 时聚合值是空的，只有这些行能说明「到底有几条数据、分别是什么样」。
         */
        private List<RawSample> rawSamples;

        /** 原始明细是否被条数上限截断；截断时说明还有更多，但没全给。 */
        private boolean rawSamplesTruncated;

        /**
         * 这一桶播放最高的那条样本。
         *
         * <p>离群样本不删：删掉爆款会掩盖「这类内容能爆」这个事实。单独列出来，
         * 是让「这一组被单条撑起来」看得见。
         */
        private RawSample extremeSample;
    }
}
