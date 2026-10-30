package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;

/** 视频版本持久层；版本只允许结果一次性写入及审核/清理 CAS。 */
public interface HeronVideoVersionMapper extends BaseMapperPlus<HeronVideoVersion, NewsVideoVersionVo> {
    default HeronVideoVersion lockById(long id) {
        return selectOne(QueryBuilder.lambda(HeronVideoVersion.class)
            .eq(HeronVideoVersion::getVersionId, id).last("FOR UPDATE").build());
    }
}
