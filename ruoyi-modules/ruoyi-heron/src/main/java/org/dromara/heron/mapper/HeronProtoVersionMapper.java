package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronProtoVersion;
import org.dromara.heron.domain.vo.ProtoVersionVo;

/** 原型版本与事件事实存储。 */
public interface HeronProtoVersionMapper extends BaseMapperPlus<HeronProtoVersion, ProtoVersionVo> {
    default HeronProtoVersion lockById(long versionId) {
        return selectOne(QueryBuilder.lambda(HeronProtoVersion.class)
            .eq(HeronProtoVersion::getVersionId, versionId).last("FOR UPDATE").build());
    }
}
