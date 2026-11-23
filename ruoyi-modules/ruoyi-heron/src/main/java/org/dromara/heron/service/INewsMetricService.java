package org.dromara.heron.service;

import org.dromara.heron.domain.bo.NewsMetricBo;
import org.dromara.heron.domain.vo.NewsMetricVo;
import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.domain.vo.NewsSuggestionVo;
import org.dromara.heron.mapper.HeronNewsMetricMapper;

import java.util.List;

public interface INewsMetricService {
    NewsMetricVo save(NewsMetricBo bo);

    List<NewsMetricVo> byVersion(long versionId);

    /** 最近若干条发布的表现，供选题器参考；没有数据时返回空表。 */
    List<HeronNewsMetricMapper.Performance> recentPerformance(int limit);

    /**
     * 归因结论：按维度分桶，给出中位数、样本等级与稳定性。
     * 结论能说到什么程度由样本量决定，样本不够只给观察，不给规律。
     */
    NewsPerformanceInsightVo performanceInsight(String dimension);

    /**
     * 创作建议：L3 质量报告里的主观条目（subjective / hypothesis）。
     * 它们不是结论，也不是预测；采纳与否由人决定。
     */
    List<NewsSuggestionVo> suggestions(int limit);
}
