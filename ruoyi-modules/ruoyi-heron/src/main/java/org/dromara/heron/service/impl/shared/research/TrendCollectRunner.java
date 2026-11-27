package org.dromara.heron.service.impl.shared.research;

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
 * 抖音热点采集的定时入口：配置里有几条 cron 就注册几个。
 *
 * <p>默认不装配（{@code heron.news.trend-enabled} 不配即为关）。采集失败只记日志，
 * 不重试也不影响任何现有链路——热点榜一天会变好几次，等下一个槽位比补跑一次更有意义。
 */
@Slf4j
public class TrendCollectRunner {

    private static final int POLL_SECONDS = 30;

    private final TrendCollectService collect;
    private final HeronNewsProperties news;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CronExpression> crons = new LinkedHashMap<>();
    private final Map<String, LocalDateTime> next = new LinkedHashMap<>();

    public TrendCollectRunner(TrendCollectService collect, HeronNewsProperties news,
                              ScheduledExecutorService scheduler) {
        this.collect = collect;
        this.news = news;
        this.scheduler = scheduler;
    }

    /** 解析并注册配置里的采集 cron 槽位；没有有效槽位时只记日志，等接口手动触发。 */
    @PostConstruct
    public void start() {
        LocalDateTime now = LocalDateTime.now();
        for (String slot : news.getTrendSlots()) {
            try {
                CronExpression expression = CronExpression.parse(slot);
                LocalDateTime due = expression.next(now);
                if (due == null) {
                    log.warn("热点采集槽位没有下次执行时间，已跳过: {}", slot);
                    continue;
                }
                crons.put(slot, expression);
                next.put(slot, due);
            } catch (RuntimeException e) {
                log.warn("热点采集槽位 cron 无效，已跳过: {}", slot);
            }
        }
        if (crons.isEmpty()) {
            log.info("抖音热点采集已启用但没有配置槽位：只能由接口手动触发");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("抖音热点采集调度已启用：{} 个槽位", crons.size());
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, CronExpression> entry : crons.entrySet()) {
            LocalDateTime due = next.get(entry.getKey());
            if (due == null || now.isBefore(due)) {
                continue;
            }
            try {
                collect.collect();
            } catch (RuntimeException e) {
                log.warn("抖音热点采集失败 slot={}: {}", entry.getKey(), e.getMessage());
            }
            LocalDateTime after = entry.getValue().next(now);
            if (after != null) {
                next.put(entry.getKey(), after);
            }
        }
    }
}
