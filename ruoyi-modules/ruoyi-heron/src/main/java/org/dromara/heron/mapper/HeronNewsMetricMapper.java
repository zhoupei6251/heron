package org.dromara.heron.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronNewsMetric;

import java.math.BigDecimal;
import java.util.List;

public interface HeronNewsMetricMapper extends BaseMapperPlus<HeronNewsMetric, HeronNewsMetric> {

    /** 每条版本只取最新一次采样，连同选题标题一起给选题器做参考。 */
    @Select("""
        select c.title as title, m.plays as plays, m.completion_rate as completionRate
        from heron_news_metric m
        join heron_video_version v on v.version_id = m.version_id
        join heron_news_work w on w.work_id = v.work_id
        join heron_news_candidate c on c.candidate_id = w.candidate_id
        where m.fetched_at = (
            select max(m2.fetched_at) from heron_news_metric m2 where m2.version_id = m.version_id)
        order by m.fetched_at desc
        limit #{limit}
        """)
    List<Performance> recentPerformance(@Param("limit") int limit);

    record Performance(String title, Long plays, BigDecimal completionRate) {
    }
}
