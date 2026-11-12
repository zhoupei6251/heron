package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.FrozenContext;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionContext;
import org.dromara.heron.mapper.HeronProductionContextMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 冻结上下文的唯一出入口：只写入一次、只校验后读取。
 *
 * <p>刻意不提供 update/delete：内容一旦冻结就不可改，「改内容」只能由上层新建一个版本
 * （新 work version → 新 context 行），旧的照旧躺在库里。所谓不可变，靠的是没有写入口，
 * 不是靠调用方自觉。
 */
@Service
@RequiredArgsConstructor
public class FrozenContextService {

    private static final String REASON_RESERVE = "reserve";

    private final HeronProductionContextMapper mapper;
    private final ObjectProvider<Clock> clocks;

    /**
     * 冻结一次生产的输入。必须在已有事务里调用（reserve 与版本、任务同生共死）。
     *
     * @param contentJson 已规范化的输入快照；原样落库，hash 由内容算出
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FrozenContext freeze(long workId, long versionId, long taskId, int contextNo,
                                long actorId, String contentJson) {
        HeronProductionContext row = new HeronProductionContext();
        row.setContextId(IdUtil.getSnowflakeNextId());
        row.setWorkId(workId);
        row.setVersionId(versionId);
        row.setTaskId(taskId);
        row.setContextNo(contextNo);
        row.setParentContextId(parentOf(workId, contextNo));
        row.setRevisionReason(REASON_RESERVE);
        row.setSchemaVersion(FrozenContext.SCHEMA);
        row.setContentHash(FrozenContext.hash(contentJson));
        row.setContentJson(contentJson);
        row.setCreateBy(actorId);
        row.setUpdateBy(actorId);
        row.setCreateTime(now());
        row.setUpdateTime(now());
        if (mapper.insert(row) != 1) {
            throw new IllegalStateException("冻结上下文写入失败");
        }
        return FrozenContext.of(row);
    }

    /** 按任务取上下文；没有就是迁移前的老任务。 */
    public FrozenContext byTask(long taskId) {
        if (taskId <= 0) {
            return null;
        }
        HeronProductionContext row = mapper.selectOne(QueryBuilder.lambda(HeronProductionContext.class)
            .eq(HeronProductionContext::getTaskId, taskId)
            .last("LIMIT 1").build());
        return row == null ? null : FrozenContext.of(row);
    }

    /**
     * 消费前校验：任务手里那份输入必须和冻结上下文逐项一致。
     *
     * <p>没有上下文行的是迁移前的老任务，按 legacy 放行（否则历史任务全部失败）；
     * 一旦有行，hash 或结构对不上就拒绝执行——宁可失败，也不要拿一份「被改过的输入」生产。
     */
    public FrozenContext verify(long taskId, String inputJson) {
        FrozenContext context = byTask(taskId);
        if (context == null) {
            return null;
        }
        if (!FrozenContext.SCHEMA.equals(context.schemaVersion())) {
            throw new HeronBusinessException(409, "news_context_schema",
                "冻结上下文版本 " + context.schemaVersion() + " 不在当前执行器支持范围");
        }
        if (!context.contentHash().equals(FrozenContext.hash(inputJson))) {
            throw new HeronBusinessException(409, "news_context_mismatch",
                "任务输入与冻结上下文不一致，拒绝执行");
        }
        return context;
    }

    /** 上一版上下文：同一作品里序号更小的最近一条。首版返回 null。 */
    private Long parentOf(long workId, int contextNo) {
        HeronProductionContext previous = mapper.selectOne(QueryBuilder.lambda(HeronProductionContext.class)
            .eq(HeronProductionContext::getWorkId, workId)
            .lt(HeronProductionContext::getContextNo, contextNo)
            .orderByDesc(HeronProductionContext::getContextNo)
            .last("LIMIT 1").build());
        return previous == null ? null : previous.getContextId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clocks.getIfAvailable(Clock::systemUTC));
    }
}
