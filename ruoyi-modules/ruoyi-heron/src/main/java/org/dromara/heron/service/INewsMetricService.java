package org.dromara.heron.service;

import org.dromara.heron.domain.bo.NewsMetricBo;
import org.dromara.heron.domain.vo.NewsMetricVo;
import org.dromara.heron.mapper.HeronNewsMetricMapper;

import java.util.List;

public interface INewsMetricService {
    NewsMetricVo save(NewsMetricBo bo);

    List<NewsMetricVo> byVersion(long versionId);

    /** 最近若干条发布的表现，供选题器参考；没有数据时返回空表。 */
    List<HeronNewsMetricMapper.Performance> recentPerformance(int limit);

    /** 按模板 / 生产线 / 叙事角度汇总表现。dimension 非法时抛业务异常，不静默返回空表。 */
    List<HeronNewsMetricMapper.Breakdown> performanceBreakdown(String dimension);
}
