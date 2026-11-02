package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.vo.NewsSourceVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 来源持久层。租约只能由持有令牌的调用方推进：任何更新都带 fetch_token 条件，
 * 迟到或已被接管的 worker 因此写不进成功与失败事实。
 */
public interface HeronNewsSourceMapper extends BaseMapperPlus<HeronNewsSource, NewsSourceVo> {

    default HeronNewsSource lockById(long sourceId) {
        return selectOne(QueryBuilder.lambda(HeronNewsSource.class)
            .eq(HeronNewsSource::getSourceId, sourceId).last("FOR UPDATE").build());
    }

    /** 领取采集租约：停用来源不领取，租约未过期时只有持有者能续。 */
    default int claim(long sourceId, String token, LocalDateTime now, LocalDateTime leaseUntil) {
        return lambda().set(HeronNewsSource::getFetchToken, token)
            .set(HeronNewsSource::getFetchLeaseUntil, leaseUntil)
            .set(HeronNewsSource::getLastAttemptAt, now).set(HeronNewsSource::getUpdateTime, now)
            .eq(HeronNewsSource::getSourceId, sourceId).eq(HeronNewsSource::getEnabled, "0")
            .and(w -> w.isNull(HeronNewsSource::getFetchToken).or().le(HeronNewsSource::getFetchLeaseUntil, now))
            .updateCount();
    }

    /** 成功定稿：释放令牌、清零失败次数并按周期排下次采集。空值一律写成 SQL 字面量，避免无类型 null 绑定。 */
    default int finalizeSuccess(long sourceId, String token, LocalDateTime succeededAt, LocalDateTime nextFetchAt) {
        return lambda().setSql("fetch_token = NULL").setSql("fetch_lease_until = NULL")
            .setSql("error_code = NULL").setSql("error_reason = NULL")
            .set(HeronNewsSource::getLastSuccessAt, succeededAt)
            .set(HeronNewsSource::getNextFetchAt, nextFetchAt)
            .set(HeronNewsSource::getFailureCount, 0)
            .set(HeronNewsSource::getUpdateTime, succeededAt)
            .eq(HeronNewsSource::getSourceId, sourceId).eq(HeronNewsSource::getFetchToken, token).updateCount();
    }

    /** 失败记账：同样释放令牌，错误原因如实入库供页面排查。 */
    default int recordFailure(long sourceId, String token, String errorCode, String errorReason,
                              LocalDateTime failedAt, LocalDateTime nextFetchAt) {
        return lambda().setSql("fetch_token = NULL").setSql("fetch_lease_until = NULL")
            .setSql("failure_count = failure_count + 1")
            .set(HeronNewsSource::getErrorCode, errorCode).set(HeronNewsSource::getErrorReason, errorReason)
            .set(HeronNewsSource::getNextFetchAt, nextFetchAt).set(HeronNewsSource::getUpdateTime, failedAt)
            .eq(HeronNewsSource::getSourceId, sourceId).eq(HeronNewsSource::getFetchToken, token).updateCount();
    }

    /** 到期清单：停用来源永不入列，从未采集过的排最前。 */
    default List<Long> dueSourceIds(LocalDateTime now, int limit) {
        return selectList(QueryBuilder.lambda(HeronNewsSource.class).eq(HeronNewsSource::getEnabled, "0")
            .and(w -> w.isNull(HeronNewsSource::getNextFetchAt).or().le(HeronNewsSource::getNextFetchAt, now))
            .orderByAsc(HeronNewsSource::getNextFetchAt, HeronNewsSource::getSourceId)
            .last("LIMIT " + limit).build())
            .stream().map(HeronNewsSource::getSourceId).toList();
    }
}
