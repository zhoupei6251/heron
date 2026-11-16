package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.bo.ResearchTaskQueryBo;
import org.dromara.heron.domain.vo.ResearchTaskVo;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.IResearchAgent;
import org.dromara.heron.service.IResearchTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 研究任务：建任务（同机会同日只建一次）与跑一次会话。
 *
 * <p>执行用「条件更新领取」而不是先查后改：只有仍处于 queued（或租约已过期的 running）的那一方
 * 能把状态改成 running，并发触发时后到者拿到 0 行，直接返回当前状态，不会重复烧模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "heron.agent", name = "enabled", havingValue = "true")
public class ResearchTaskServiceImpl implements IResearchTaskService {

    /** 租约时长：超过这个时间没心跳，视为执行方已经挂了。 */
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int MAX_REASON = 2000;

    private final HeronResearchTaskMapper researchTaskMapper;
    private final IResearchAgent researchAgent;
    private final ObjectProvider<Clock> clocks;

    /** 研究是长任务，触发接口不能同步等它跑完。 */
    private final ExecutorService runner = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "heron-research-runner");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public long createFor(long opportunityId, String goal) {
        LocalDateTime now = now();
        String dedup = dedupKey(opportunityId, now.toLocalDate());
        HeronResearchTask existing = researchTaskMapper.selectOne(QueryBuilder
            .lambda(HeronResearchTask.class)
            .eq(HeronResearchTask::getDedupKey, dedup)
            .last("LIMIT 1")
            .build());
        if (existing != null) {
            return existing.getResearchId();
        }

        long researchId = IdUtil.getSnowflakeNextId();
        // 定时与脚本入口没有登录态，这时才记 0；人工触发要留真实操作人
        long actor = currentUserId();
        HeronResearchTask row = new HeronResearchTask();
        row.setResearchId(researchId);
        row.setResearchNo("R" + researchId);
        row.setOpportunityId(opportunityId);
        row.setOwnerId(actor);
        row.setActorId(actor);
        // 机会 id 必须进目标：模型只有按 id 查的工具，目标里不带 id 它会直接反问 id，一次工具都不调
        row.setGoal("机会 " + opportunityId + " 在候选里。" + goal);
        row.setSnapshotRef(String.valueOf(opportunityId));
        row.setGrade("L2");
        row.setStatus(HeronResearchTask.STATUS_QUEUED);
        row.setAttemptNo(0);
        // 会话标识在创建时定死：同一个任务重试才会续上同一段上下文
        row.setSessionId("research-" + researchId);
        row.setDedupKey(dedup);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        researchTaskMapper.insert(row);
        return researchId;
    }

    @Override
    public String run(long researchId) {
        HeronResearchTask task = researchTaskMapper.selectById(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        LocalDateTime now = now();
        int attempt = (task.getAttemptNo() == null ? 0 : task.getAttemptNo()) + 1;
        // 领取得认「租约已过期的 running」：执行方挂掉后，这一行不能永远卡在 running
        int claimed = researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
            .set(HeronResearchTask::getAttemptNo, attempt)
            .set(HeronResearchTask::getLeaseUntil, now.plus(LEASE))
            .set(HeronResearchTask::getHeartbeatAt, now)
            .set(HeronResearchTask::getUpdateTime, now)
            .eq(HeronResearchTask::getResearchId, researchId)
            .and(where -> where
                .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
                .or(stale -> stale
                    .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
                    .lt(HeronResearchTask::getLeaseUntil, now)))
            .updateCount();
        if (claimed == 0) {
            // 别人正在跑、已结束或以被取消：不重复跑，直接返回现状（重读，别返回领取前的旧值）
            HeronResearchTask current = find(researchId);
            return current == null ? task.getStatus() : current.getStatus();
        }

        try (ResearchLeaseKeeper keeper =
                 new ResearchLeaseKeeper(researchTaskMapper, researchId, clock(), LEASE)) {
            keeper.start();
            IResearchAgent.Outcome outcome = researchAgent.research(
                task.getSessionId(), researchId, attempt, task.getGoal(), keeper::cancelled);

            String status;
            if (keeper.cancelled() || rowCancelledSince(researchId)) {
                // 取消优先于完成：短任务可能在续命线程下一拍之前就跑完了，
                // 这时库里已经是 cancelled，用户要的是「别用这个结果」，不是「其实做完了」
                status = HeronResearchTask.STATUS_CANCELLED;
            } else if (outcome.budgetExceeded() || (outcome.maxItersReached() && !outcome.stoppedEarly())) {
                // 预算到点或轮数跑满：结论可用但不完整。
                // 模型自己主动收尾时不算被截断——它已经判断「证据够了」
                status = HeronResearchTask.STATUS_PARTIAL;
            } else {
                status = HeronResearchTask.STATUS_SUCCEEDED;
            }
            researchTaskMapper.lambda()
                .set(HeronResearchTask::getStatus, status)
                .set(HeronResearchTask::getResultText, outcome.text())
                .set(HeronResearchTask::getRounds, outcome.rounds())
                .set(HeronResearchTask::getToolCalls, outcome.toolCalls())
                .set(HeronResearchTask::getInputTokens, outcome.inputTokens())
                .set(HeronResearchTask::getOutputTokens, outcome.outputTokens())
                .set(HeronResearchTask::getDecisionType, outcome.decision())
                .set(HeronResearchTask::getConfidence, outcome.confidence())
                .set(HeronResearchTask::getErrorCode, null)
                .set(HeronResearchTask::getErrorReason, null)
                .set(HeronResearchTask::getLeaseUntil, null)
                .set(HeronResearchTask::getUpdateTime, now())
                .eq(HeronResearchTask::getResearchId, researchId)
                .update();
            return status;
        } catch (RuntimeException e) {
            log.warn("研究任务失败 researchId={} cause={}", researchId, e.getMessage());
            researchTaskMapper.lambda()
                .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_FAILED)
                .set(HeronResearchTask::getErrorCode, "research_failed")
                .set(HeronResearchTask::getErrorReason, truncate(e.getMessage()))
                .set(HeronResearchTask::getLeaseUntil, null)
                .set(HeronResearchTask::getUpdateTime, now())
                .eq(HeronResearchTask::getResearchId, researchId)
                .update();
            return HeronResearchTask.STATUS_FAILED;
        }
    }

    @Override
    public HeronResearchTask find(long researchId) {
        return researchTaskMapper.selectById(researchId);
    }

    @Override
    public HeronResearchTask trigger(long researchId) {
        HeronResearchTask task = find(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        runner.submit(() -> {
            try {
                run(researchId);
            } catch (RuntimeException e) {
                log.warn("研究任务异步执行异常 researchId={} cause={}", researchId, e.getMessage());
            }
        });
        return task;
    }

    @Override
    public HeronResearchTask requeue(long researchId) {
        if (find(researchId) == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        // 只回退终态：执行中或排队中的重复点击不该把它打回队列
        researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
            .set(HeronResearchTask::getErrorCode, null)
            .set(HeronResearchTask::getErrorReason, null)
            .set(HeronResearchTask::getUpdateTime, now())
            .eq(HeronResearchTask::getResearchId, researchId)
            .in(HeronResearchTask::getStatus, List.of(
                HeronResearchTask.STATUS_SUCCEEDED,
                HeronResearchTask.STATUS_PARTIAL,
                HeronResearchTask.STATUS_FAILED,
                HeronResearchTask.STATUS_CANCELLED))
            .updateCount();
        return trigger(researchId);
    }

    @Override
    public HeronResearchTask cancel(long researchId) {
        HeronResearchTask task = find(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_CANCELLED)
            .set(HeronResearchTask::getUpdateTime, now())
            .eq(HeronResearchTask::getResearchId, researchId)
            .and(where -> where
                .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
                .or(running -> running.eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)))
            .updateCount();
        return find(researchId);
    }

    @Override
    public PageResult<ResearchTaskVo> queryPageList(ResearchTaskQueryBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<HeronResearchTask> wrapper = QueryBuilder
            .lambda(HeronResearchTask.class)
            .eqIfPresent(HeronResearchTask::getOpportunityId, bo.getOpportunityId())
            .eqIfText(HeronResearchTask::getStatus, bo.getStatus())
            .eqIfText(HeronResearchTask::getDecisionType, bo.getDecisionType())
            .orderByDesc(HeronResearchTask::getCreateTime)
            .build();
        var page = researchTaskMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    /** 执行期间是否被取消过：续命线程来不及看到时，靠这一次回读兜住。 */
    private boolean rowCancelledSince(long researchId) {
        HeronResearchTask current = researchTaskMapper.selectById(researchId);
        return current != null && HeronResearchTask.STATUS_CANCELLED.equals(current.getStatus());
    }

    /** 同一机会同一天只研究一次：日期参与摘要，跨天才有新任务。 */
    static String dedupKey(long opportunityId, LocalDate date) {
        return DigestUtil.sha256Hex(opportunityId + "|" + date);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    /** 无登录上下文（定时/脚本）时返回 0，不抛异常。 */
    private static long currentUserId() {
        try {
            Long userId = LoginHelper.getUserId();
            return userId == null ? 0L : userId;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock());
    }

    private Clock clock() {
        Clock clock = clocks.getIfAvailable();
        return clock == null ? Clock.systemDefaultZone() : clock;
    }
}
