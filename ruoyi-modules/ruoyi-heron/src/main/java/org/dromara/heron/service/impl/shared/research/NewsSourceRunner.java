package org.dromara.heron.service.impl.shared.research;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.service.INewsSourceService;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 到期采集调度。任务体不得抛出异常，否则固定延时的 future 会被取消。
 */
@Slf4j
public class NewsSourceRunner {

    private static final int POLL_SECONDS = 30;
    private final INewsSourceService sources;
    private final ScheduledExecutorService scheduler;

    public NewsSourceRunner(INewsSourceService sources,
                           @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        this.sources = sources;
        this.scheduler = scheduler;
    }

    /** 注册每 30 秒一次的 {@code collectDue} 轮询；单个来源失败已在服务内记入来源行，不会中断调度。 */
    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("Heron 新闻来源采集调度已启用");
    }

    void tick() {
        try {
            sources.collectDue();
        } catch (RuntimeException e) {
            log.warn("新闻来源采集调度失败", e);
        }
    }
}
