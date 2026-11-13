package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.mapper.HeronResearchTaskMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 研究执行期间的租约续命与取消探测。
 *
 * <p>为什么需要它：一次研究最长跑 10 分钟，而租约只有 5 分钟。没有续命，跑到一半就会被当成
 * 死任务回收。所以续命不是可选优化，是租约回收能安全存在的前提。
 *
 * <p>取消也在这里探测：库里状态被改成 {@code cancelled} 后置位一个 flag，工具入口据此拒掉后续调用。
 * 已经发出去的模型调用拦不住，但能立刻停止继续烧钱，并且让模型基于已有信息收尾。
 *
 * <p>每次研究一个 daemon 线程、跑完即关：研究量级是「一天几条」，不值得为它建调度中心。
 */
final class ResearchLeaseKeeper implements AutoCloseable {

    /** 探测间隔：也是取消的最长生效延迟。 */
    private static final long TICK_SECONDS = 15;

    private final HeronResearchTaskMapper mapper;
    private final long researchId;
    private final Clock clock;
    private final Duration lease;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private ScheduledExecutorService ticker;

    ResearchLeaseKeeper(HeronResearchTaskMapper mapper, long researchId, Clock clock, Duration lease) {
        this.mapper = mapper;
        this.researchId = researchId;
        this.clock = clock;
        this.lease = lease;
    }

    void start() {
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "research-lease-" + researchId);
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** 包内可见：单测直接驱动一拍，不必真的等 15 秒。 */
    void tick() {
        try {
            HeronResearchTask row = mapper.selectById(researchId);
            if (row == null) {
                return;
            }
            if (HeronResearchTask.STATUS_CANCELLED.equals(row.getStatus())) {
                cancelled.set(true);
                return;
            }
            if (!HeronResearchTask.STATUS_RUNNING.equals(row.getStatus())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            mapper.lambda()
                .set(HeronResearchTask::getHeartbeatAt, now)
                .set(HeronResearchTask::getLeaseUntil, now.plus(lease))
                .set(HeronResearchTask::getUpdateTime, now)
                .eq(HeronResearchTask::getResearchId, researchId)
                .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
                .updateCount();
        } catch (RuntimeException ignored) {
            // 续命失败不打断研究：最坏情况是租约过期，下次重跑把它领回来
        }
    }

    boolean cancelled() {
        return cancelled.get();
    }

    @Override
    public void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }
}
