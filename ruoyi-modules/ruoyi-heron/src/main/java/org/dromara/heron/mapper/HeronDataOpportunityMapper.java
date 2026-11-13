package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.vo.DataOpportunityVo;

import java.time.LocalDateTime;

/** 数据机会：唯一键是 dedup_key（源 + 实体 + 窗口 + 信号类型 + 日期），重复分析走更新。 */
public interface HeronDataOpportunityMapper extends BaseMapperPlus<HeronDataOpportunity, DataOpportunityVo> {

    /**
     * 机会被做成片子之后置为 used 并记下版本号。
     * 不置状态的话，同一天的下一个槽位还会把它再挑一次，生产同一条内容。
     */
    default void markUsed(long opportunityId, long versionId, LocalDateTime now) {
        lambda().set(HeronDataOpportunity::getStatus, "used")
            .set(HeronDataOpportunity::getUsedVersionId, versionId)
            .set(HeronDataOpportunity::getUpdateTime, now)
            .eq(HeronDataOpportunity::getOpportunityId, opportunityId)
            .update();
    }

    /** 落选时也要能查回来：按 id 取一条。 */
    default HeronDataOpportunity selectById(long opportunityId) {
        return selectOne(QueryBuilder.lambda(HeronDataOpportunity.class)
            .eq(HeronDataOpportunity::getOpportunityId, opportunityId).build());
    }
}
