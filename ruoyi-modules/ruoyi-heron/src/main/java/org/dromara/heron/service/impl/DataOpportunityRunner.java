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
 * 数据机会分析的定时入口：配置里有几条 cron 就注册几个。
 *
 * <p>默认不装配（{@code heron.news.data-analysis-enabled} 不配即为关）。第一步只产机会、
 * 不接入生产，所以这个调度器在没人显式打开之前不会跑，也不影响任何现有链路。
 */
@Slf4j
public class DataOpportunityRunner {

    private static final int POLL_SECONDS = 30;

    private final DataOpportunityService opportunities;
    private final HeronNewsProperties news;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CronExpression> crons = new LinkedHashMap<>();
    private final Map<String, LocalDateTime> next = new LinkedHashMap<>();

    public DataOpportunityRunner(DataOpportunityService opportunities, HeronNewsProperties news,
                                 ScheduledExecutorService scheduler) {
        this.opportunities = opportunities;
        this.news = news;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void start() {
        LocalDateTime now = LocalDateTime.now();
        for (String slot : news.getDataAnalysisSlots()) {
            try {
                CronExpression expression = CronExpression.parse(slot);
                LocalDateTime due = expression.next(now);
                if (due == null) {
                    log.warn("数据机会分析槽位没有下次执行时间，已跳过: {}", slot);
                    continue;
                }
                crons.put(slot, expression);
                next.put(slot, due);
            } catch (RuntimeException e) {
                log.warn("数据机会分析槽位 cron 无效，已跳过: {}", slot);
            }
        }
        if (crons.isEmpty()) {
            log.info("数据机会分析已启用但没有配置槽位：只能由接口手动触发");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("数据机会分析调度已启用：{} 个槽位", crons.size());
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, CronExpression> entry : crons.entrySet()) {
            LocalDateTime due = next.get(entry.getKey());
            if (due == null || now.isBefore(due)) {
                continue;
            }
            try {
                DataOpportunityService.Report report = opportunities.analyze(news.getDataAnalysisWindowPoints());
                log.info("数据机会分析完成 slot={} 源 {} 有效 {} 合并掉 {} 入榜 {}+{} 跳过 {} 失败 {} 不可用源 {}",
                    entry.getKey(), report.sources(), report.analyzed(), report.mergedAway(),
                    report.created(), report.updated(), report.skipped(), report.failed(),
                    report.unhealthy());
            } catch (RuntimeException e) {
                // 分析失败不能影响调度线程：下一槽位照常触发
                log.warn("数据机会分析槽位执行异常: {}", entry.getKey(), e);
            }
            LocalDateTime after = entry.getValue().next(now);
            if (after != null) {
                next.put(entry.getKey(), after);
            }
        }
    }
}
