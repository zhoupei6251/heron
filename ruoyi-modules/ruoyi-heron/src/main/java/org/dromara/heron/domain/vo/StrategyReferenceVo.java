package org.dromara.heron.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史表现参考卡：只给人看，不进提示词。
 *
 * <p>参考卡不是自动开关：它把「已有样本里这一组的表现如何」摆出来，用不用由总编辑自己决定，
 * 而且用不用都要写进决策理由。表现数据自动进提示词等于给模型一个策略倾向，
 * 那和 L3 禁止的「用印象代替证据」是同一件事。
 */
@Data
public class StrategyReferenceVo {

    /** 卡的结构版本。 */
    private String schemaVersion;

    /** 生成时间。 */
    private LocalDateTime generatedAt;

    /** 归因维度。 */
    private String dimension;

    /** 该维度下正在看的分组值。 */
    private String bucketValue;

    /** 整体证据等级 L0~L4。 */
    private String tier;

    /** 这个维度是否被人工锁定。锁定时不给聚合结论。 */
    private boolean locked;

    /** 锁定理由；锁定时必填。 */
    private String lockReason;

    /** 锁定到期时间；没有期限的锁不允许存在。 */
    private LocalDateTime scopeUntil;

    /** 各分组的结论明细。 */
    private List<Bucket> buckets;

    /** 这一页能说到什么程度，写给人看。 */
    private String notice;

    /**
     * 一个分组的结论。
     *
     * <p>样本不足时聚合值是空的：给出中位数等于把噪声说成结论。
     */
    @Data
    public static class Bucket {

        /** 分组值。 */
        private String bucket;

        /** 样本条数。 */
        private int samples;

        /** 证据等级 L0~L4。 */
        private String tier;

        /** 播放中位数；样本不足时为空。 */
        private Double medianPlays;

        /** 播放下四分位；样本不足时为空。 */
        private Double p25Plays;

        /** 播放上四分位；样本不足时为空。 */
        private Double p75Plays;

        /** 最高播放 / 中位播放；大于 5 说明这一组被单条极端值主导。 */
        private Double outlierRatio;

        /** 去掉最高的一条后方向是否还成立。 */
        private boolean stable;

        /** 是否可直接进入内容策略（只有 L4 且稳定才可以）。 */
        private boolean strategyEligible;

        /** 一句话结论，写清依据和保留。 */
        private String summary;
    }
}
