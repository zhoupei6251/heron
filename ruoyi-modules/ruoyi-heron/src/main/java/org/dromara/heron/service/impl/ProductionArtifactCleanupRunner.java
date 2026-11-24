package org.dromara.heron.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 云端孤儿元数据保留 15 天；仍存在的对象必须走带发布保护的版本清理。
 */
@Slf4j
public class ProductionArtifactCleanupRunner {

    private static final int POLL_SECONDS = 3600;
    private static final Duration RETENTION = Duration.ofDays(15);
    private final ProductionArtifactService artifacts;
    private final ScheduledExecutorService scheduler;

    public ProductionArtifactCleanupRunner(ProductionArtifactService artifacts,
                                           @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        this.artifacts = artifacts;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("Heron 云端孤儿元数据 15 天保留清理调度已启用");
    }

    void tick() {
        try {
            artifacts.cleanupExpiredUploads(RETENTION);
        } catch (RuntimeException e) {
            log.warn("云端产物过期清理调度失败", e);
        }
    }
}
