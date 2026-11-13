package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.HeronDataSourceVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源注册表：登记走 SQL 迁移，这里只维护运行期健康状态。
 *
 * <p>健康状态必须由真实取数结果写回——迁移里那个 {@code ok} 只是登记时人工探一次的结论，
 * 源失效后不会自己变，最后变成「注册表说能用、实际全失败」。
 */
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

    /** 取数成功：清零失败计数，恢复 ok，抹掉上一次的失败原因。 */
    default void markHealthy(String sourceKey, LocalDateTime now) {
        lambda().set(HeronDataSource::getProbeStatus, "ok")
            .set(HeronDataSource::getFailureCount, 0)
            .set(HeronDataSource::getProbeNote, null)
            .set(HeronDataSource::getUpdateTime, now)
            .eq(HeronDataSource::getSourceKey, sourceKey).update();
    }

    /** 取数失败：计数 +1；连续失败够多才判 blocked，单次抖动只留痕。 */
    default void markFailed(String sourceKey, int failures, String status, String note, LocalDateTime now) {
        lambda().set(HeronDataSource::getFailureCount, failures)
            .set(HeronDataSource::getProbeStatus, status)
            .set(HeronDataSource::getProbeNote, note)
            .set(HeronDataSource::getUpdateTime, now)
            .eq(HeronDataSource::getSourceKey, sourceKey).update();
    }
}
