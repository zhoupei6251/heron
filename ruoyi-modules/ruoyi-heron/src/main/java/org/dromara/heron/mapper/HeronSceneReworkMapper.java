package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronSceneRework;

/** 场景返工审计持久层；与版本结果一起提交，不提供更新或删除入口。 */
public interface HeronSceneReworkMapper extends BaseMapperPlus<HeronSceneRework, HeronSceneRework> {
}
