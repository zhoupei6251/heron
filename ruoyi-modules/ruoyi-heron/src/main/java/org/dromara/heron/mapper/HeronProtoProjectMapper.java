package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronProtoProject;
import org.dromara.heron.domain.vo.ProtoProjectVo;

/** 原型项目锚点，必须在事务内按 lane → project → task → version 锁序调用。 */
public interface HeronProtoProjectMapper extends BaseMapperPlus<HeronProtoProject, ProtoProjectVo> {
    default HeronProtoProject lockById(long projectId) {
        return selectOne(QueryBuilder.lambda(HeronProtoProject.class)
            .eq(HeronProtoProject::getProjectId, projectId).last("FOR UPDATE").build());
    }
}
