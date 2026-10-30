package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.vo.NewsWorkVo;

/** 作品持久层；candidateId 唯一关系不可变。 */
public interface HeronNewsWorkMapper extends BaseMapperPlus<HeronNewsWork, NewsWorkVo> {
    default HeronNewsWork lockById(long id) {
        return selectOne(QueryBuilder.lambda(HeronNewsWork.class)
            .eq(HeronNewsWork::getWorkId, id).last("FOR UPDATE").build());
    }
    default HeronNewsWork byCandidate(long candidateId) {
        return selectOne(QueryBuilder.lambda(HeronNewsWork.class)
            .eq(HeronNewsWork::getCandidateId, candidateId).build());
    }
}
