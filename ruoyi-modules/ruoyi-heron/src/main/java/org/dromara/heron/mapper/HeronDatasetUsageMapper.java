package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronDatasetUsage;
import org.dromara.heron.domain.vo.HeronDatasetUsageVo;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

/** 数据看板使用账本；去重先按 source_key，等单源多实体上线再收到 entity。 */
public interface HeronDatasetUsageMapper extends BaseMapperPlus<HeronDatasetUsage, HeronDatasetUsageVo> {

    default Set<String> recentSourceKeys(LocalDateTime since) {
        return selectList(QueryBuilder.lambda(HeronDatasetUsage.class)
            .ge(HeronDatasetUsage::getUsedAt, since).build())
            .stream().map(HeronDatasetUsage::getSourceKey).collect(Collectors.toSet());
    }
}
