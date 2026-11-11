package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.HeronDataSourceVo;

import java.util.List;

/** 数据源注册表读取；不提供写入，登记走 SQL 迁移。 */
public interface HeronDataSourceMapper extends BaseMapperPlus<HeronDataSource, HeronDataSourceVo> {

    default HeronDataSource selectByKey(String sourceKey) {
        return selectOne(QueryBuilder.lambda(HeronDataSource.class)
            .eq(HeronDataSource::getSourceKey, sourceKey).build());
    }

    default List<HeronDataSource> enabledSources() {
        return selectList(QueryBuilder.lambda(HeronDataSource.class)
            .eq(HeronDataSource::getEnabled, "0").build());
    }

    default List<String> enabledKeys() {
        return enabledSources().stream().map(HeronDataSource::getSourceKey).toList();
    }
}
