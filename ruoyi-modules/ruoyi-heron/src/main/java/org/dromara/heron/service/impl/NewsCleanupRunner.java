package org.dromara.heron.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 过期成片定时清理：本地副本保留 30 天，到期由 {@code cleanupExpired} 按版本删除。
 * 发布保护、活动任务与 .keep 都在清理内部判定；任务体不得抛出异常，否则固定延时的 future 会被取消。
 */
@Slf4j
public class NewsCleanupRunner {

    private static final int POLL_SECONDS = 3600;
    private final NewsWorkServiceImpl maintenance;
    private final ScheduledExecutorService scheduler;

    public NewsCleanupRunner(NewsWorkServiceImpl maintenance,
                             @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        this.maintenance = maintenance;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("Heron 成片 30 天保留清理调度已启用");
    }

    void tick() {
        try {
            maintenance.cleanupExpired(false);
        } catch (RuntimeException e) {
            log.warn("成片过期清理调度失败", e);
        }
    }
}