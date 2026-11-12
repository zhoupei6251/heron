package org.dromara.heron.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronNewsMetric;

import java.math.BigDecimal;
import java.util.List;

public interface HeronNewsMetricMapper extends BaseMapperPlus<HeronNewsMetric, HeronNewsMetric> {

    /** 每条版本只取最新一次采样，连同选题标题给选题器参考。SQL 在 mapper/heron/HeronNewsMetricMapper.xml。 */
    List<Performance> recentPerformance(@Param("limit") int limit);

    /**
     * 按维度汇总表现。groupBy 取 template / line / angle——三个维度共用一条 SQL，
     * 加维度改 XML 里的一行比复制三份语句更不容易漂移。
     */
    List<Breakdown> performanceBreakdown(@Param("groupBy") String groupBy);

    record Performance(String title, Long plays, BigDecimal completionRate) {
    }

    /** 一个分组的表现汇总。样本数一起给出：只有一条数据的平均值不该被当成规律。 */
    record Breakdown(String bucket, Long samples, Double avgPlays,
                     Double avgCompletionRate, Double avgLikes, Long maxPlays) {
    }
}
