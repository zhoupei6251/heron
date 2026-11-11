package org.dromara.heron.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 自动产出调度：配置里有几条 cron 就注册几个槽位，每条触发一次「最多产出一条」。
 * 改槽位要改配置并重启，这是刻意的——定时是部署决定，不是运行期状态。
 */
@Slf4j
public class NewsAutoRunner {
    private static final int POLL_SECONDS = 30;
    private static final int SWEEP_SECONDS = 60;

    private final NewsAutoService auto;
    private final HeronNewsProperties news;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CronExpression> crons = new LinkedHashMap<>();
    private final Map<String, LocalDateTime> next = new LinkedHashMap<>();

    public NewsAutoRunner(NewsAutoService auto, HeronNewsProperties news, ScheduledExecutorService scheduler) {
        this.auto = auto;
        this.news = news;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void start() {
        LocalDateTime now = LocalDateTime.now();
        for (String slot : news.getAutoSlots()) {
            try {
                CronExpression expression = CronExpression.parse(slot);
                LocalDateTime due = expression.next(now);
                if (due == null) {
                    log.warn("自动产出槽位没有下次执行时间，已跳过: {}", slot);
                    continue;
                }
                crons.put(slot, expression);
                next.put(slot, due);
            } catch (RuntimeException e) {
                log.warn("自动产出槽位 cron 无效，已跳过: {}", slot);
            }
        }
        if (!crons.isEmpty()) {
            scheduler.scheduleWithFixedDelay(this::tickSlots, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            log.info("Heron 自动产出调度已启用：{} 个槽位", crons.size());
        }
        scheduler.scheduleWithFixedDelay(this::sweep, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    private void tickSlots() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, CronExpression> entry : crons.entrySet()) {
            LocalDateTime due = next.get(entry.getKey());
            if (due == null || now.isBefore(due)) {
                continue;
            }
            try {
                auto.tick(entry.getKey());
            } catch (RuntimeException e) {
                // 槽位体自己会把失败写进槽位表；这里只兜住调度线程不被取消
                log.warn("自动产出槽位执行异常: {}", entry.getKey(), e);
            }
            LocalDateTime after = entry.getValue().next(now);
            if (after != null) {
                next.put(entry.getKey(), after);
            }
        }
    }

    private void sweep() {
        try {
            auto.confirmPending();
        } catch (RuntimeException e) {
            log.warn("自动确认扫描异常", e);
        }
    }
}
