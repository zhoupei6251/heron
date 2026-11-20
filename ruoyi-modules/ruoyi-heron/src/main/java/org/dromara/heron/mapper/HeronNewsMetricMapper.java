package org.dromara.heron.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronNewsMetric;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface HeronNewsMetricMapper extends BaseMapperPlus<HeronNewsMetric, HeronNewsMetric> {

    /** 每条版本只取最新一次采样，连同选题标题给选题器参考。SQL 在 mapper/heron/HeronNewsMetricMapper.xml。 */
    List<Performance> recentPerformance(@Param("limit") int limit);

    /**
     * 每版最新一次采样的明细行，带上归因需要的创作属性。
     *
     * <p>明细取回 Java 侧算分位数：MySQL 8 没有 median()，在 SQL 里拼分位数只会把
     * 一条语句写得既长又不可读，聚合口径还得在两处各维护一遍。
     */
    List<PerformanceSample> performanceSamples();

    record Performance(String title, Long plays, BigDecimal completionRate) {
    }

    /** 一条版本的一次采样，连同能直接读出来的创作属性。 */
    record PerformanceSample(Long versionId, Long plays, Double completionRate, Long likes,
                             Long comments, Long shares, LocalDateTime fetchedAt,
                             LocalDateTime publishAt, Integer durationMs,
                             String inputSnapshot, String script, String contentProfile,
                             String sourceKind, Long candidateId) {
    }
}
