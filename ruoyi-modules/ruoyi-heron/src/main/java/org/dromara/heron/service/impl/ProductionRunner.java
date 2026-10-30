package org.dromara.heron.service.impl;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionFailureBo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.IProductionPipeline;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量调度：每条流水线最多一个本地执行槽，心跳失败即停止，无法确认就不改终态。
 */
@Slf4j
public class ProductionRunner {

    private static final int POLL_SECONDS = 5;
    private static final int HEARTBEAT_SECONDS = 10;

    private final ApplicationContext pipelines;
    private final IProductionExecutionService execution;
    private final ProductionProcessService processes;
    private final ProductionRecoveryService recovery;
    private final ProductionUsageService usage;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "heron-production-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<ProductionBizType, AtomicBoolean> busy = new EnumMap<>(ProductionBizType.class);

    public ProductionRunner(ApplicationContext pipelines,
                            IProductionExecutionService execution,
                            ProductionProcessService processes,
                            ProductionRecoveryService recovery,
                            ProductionUsageService usage,
                            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        this.pipelines = pipelines;
        this.execution = execution;
        this.processes = processes;
        this.recovery = recovery;
        this.usage = usage;
        this.scheduler = scheduler;
        for (ProductionBizType type : ProductionBizType.values()) {
            busy.put(type, new AtomicBoolean());
        }
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 0, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("Heron 生产 worker 已启用");
    }

    @PreDestroy
    public void stop() {
        workers.shutdownNow();
    }

    void tick() {
        try {
            execution.recoverExpired();
            recovery.recoverCancelling();
        } catch (RuntimeException e) {
            log.warn("生产恢复扫描失败", e);
        }
        for (ProductionBizType type : ProductionBizType.values()) {
            try {
                poll(type);
            } catch (RuntimeException e) {
                log.warn("生产领取失败: type={}", type, e);
            }
        }
    }

    public boolean poll(ProductionBizType type) {
        AtomicBoolean slot = busy.get(type);
        if (slot == null || !slot.compareAndSet(false, true)) {
            return false;
        }
        IProductionPipeline pipeline = pipeline(type);
        if (pipeline == null) {
            slot.set(false);
            return false;
        }
        Optional<ProductionRunVo> claimed;
        try {
            claimed = execution.claim(type);
        } catch (RuntimeException e) {
            slot.set(false);
            throw e;
        }
        if (claimed.isEmpty()) {
            slot.set(false);
            return false;
        }
        ProductionRunVo run = claimed.get();
        try {
            workers.execute(() -> {
                try {
                    execute(run, pipeline);
                } finally {
                    slot.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            slot.set(false);
            log.warn("本地执行槽已满，等待租约恢复: taskId={}", run.taskId());
        }
        return true;
    }

    public boolean runOnce(ProductionBizType type) {
        IProductionPipeline pipeline = pipeline(type);
        if (pipeline == null) {
            return false;
        }
        Optional<ProductionRunVo> claimed = execution.claim(type);
        if (claimed.isEmpty()) {
            return false;
        }
        execute(claimed.get(), pipeline);
        return true;
    }

    private void execute(ProductionRunVo run, IProductionPipeline pipeline) {
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!execution.heartbeat(run.taskId(), run.runToken())) {
                    processes.stopAndConfirm(run);
                    worker.interrupt();
                }
            } catch (RuntimeException e) {
                log.warn("生产心跳失败，停止本地执行: taskId={}", run.taskId());
                processes.stopAndConfirm(run);
                worker.interrupt();
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        boolean applied = false;
        try {
            ProductionResultVo result = pipeline.execute(run);
            applied = execution.succeed(run, result);
        } catch (HeronBusinessException e) {
            applied = failWithUsage(run, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("生产流水线执行失败: taskId={}", run.taskId(), e);
            applied = failWithUsage(run, "production_pipeline_error", "流水线执行失败");
        } finally {
            heartbeat.cancel(false);
        }

        if (!applied) {
            Thread.interrupted();
            recovery.finishCancelledRun(run);
        }
    }

    private boolean failWithUsage(ProductionRunVo run, String code, String reason) {
        boolean externalUnknown;
        try {
            externalUnknown = usage.startedCount(run.taskId(), run.attemptNo()) > 0;
        } catch (RuntimeException e) {
            log.warn("读取外部调用状态失败: taskId={}", run.taskId());
            externalUnknown = true;
        }
        if (externalUnknown) {
            try {
                usage.markAttemptUnknownOnFailure(run);
            } catch (RuntimeException e) {
                log.warn("外部调用标记未知失败: taskId={}", run.taskId());
            }
        }
        try {
            return execution.fail(run, new ProductionFailureBo(code, null, null, reason, externalUnknown));
        } catch (HeronBusinessException e) {
            return false;
        }
    }

    private IProductionPipeline pipeline(ProductionBizType type) {
        String beanName = type == ProductionBizType.news_video
            ? "newsProductionPipeline" : "prototypeProductionPipeline";
        try {
            return pipelines.getBean(beanName, IProductionPipeline.class);
        } catch (org.springframework.beans.BeansException e) {
            return null;
        }
    }
}
