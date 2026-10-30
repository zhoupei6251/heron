package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.vo.NewsCandidateVo;

/** 候选持久层，调用方须先获取 news lane。 */
public interface HeronNewsCandidateMapper extends BaseMapperPlus<HeronNewsCandidate, NewsCandidateVo> {
    default HeronNewsCandidate lockById(long id) {
        return selectOne(QueryBuilder.lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getCandidateId, id).last("FOR UPDATE").build());
    }
}
