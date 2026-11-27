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
 * 自动产出与发布调度：配置里有几条 cron 就注册几个槽位，每条触发一次「最多产出/发布一条」。
 * 发布槽位与产出槽位同构（publish-slots 也是 cron 列表，几条就是每天最多发几条、什么时候发），
 * 有积压时不会每分钟发一条——每个发布时段只发一条，量由部署侧显式定，不是调度扫描频率的副作用。
 * 改槽位要改配置并重启，这是刻意的——定时是部署决定，不是运行期状态。
 */
@Slf4j
public class NewsAutoRunner {
    private static final int POLL_SECONDS = 30;

    private final NewsAutoService auto;
    private final HeronNewsProperties news;
    private final PublishScheduler publisher;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CronExpression> crons = new LinkedHashMap<>();
    private final Map<String, LocalDateTime> next = new LinkedHashMap<>();
    private final Map<String, CronExpression> publishCrons = new LinkedHashMap<>();
    private final Map<String, LocalDateTime> publishNext = new LinkedHashMap<>();

    public NewsAutoRunner(NewsAutoService auto, HeronNewsProperties news, PublishScheduler publisher,
                         ScheduledExecutorService scheduler) {
        this.auto = auto;
        this.news = news;
        this.publisher = publisher;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void start() {
        LocalDateTime now = LocalDateTime.now();
        for (String slot : news.getAutoSlots()) {
            registerSlot(crons, next, slot, now, "自动产出槽位");
        }
        for (String slot : news.getPublishSlots()) {
            registerSlot(publishCrons, publishNext, slot, now, "发布槽位");
        }
        if (!crons.isEmpty()) {
            scheduler.scheduleWithFixedDelay(this::tickSlots, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            log.info("Heron 自动产出调度已启用：{} 个槽位", crons.size());
        }
        if (!publishCrons.isEmpty()) {
            scheduler.scheduleWithFixedDelay(this::tickPublishSlots, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            log.info("Heron 发布调度已启用：{} 个时段（每日最多发布 {} 条）", publishCrons.size(), publishCrons.size());
        }
    }

    private void registerSlot(Map<String, CronExpression> target, Map<String, LocalDateTime> targetNext,
                              String slot, LocalDateTime now, String what) {
        try {
            CronExpression expression = CronExpression.parse(slot);
            LocalDateTime due = expression.next(now);
            if (due == null) {
                log.warn("{}没有下次执行时间，已跳过: {}", what, slot);
                return;
            }
            target.put(slot, expression);
            targetNext.put(slot, due);
        } catch (RuntimeException e) {
            log.warn("{} cron 无效，已跳过: {}", what, slot);
        }
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

    /** 发布时段：到点只发一条（择优），间隔期间库里再多的积压也不发。 */
    private void tickPublishSlots() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, CronExpression> entry : publishCrons.entrySet()) {
            LocalDateTime due = publishNext.get(entry.getKey());
            if (due == null || now.isBefore(due)) {
                continue;
            }
            try {
                // 自动发布只走库存择优：旧 confirmPending 旁路已停，
                // 没过 Curator 评分的成片不再被「生产完即发布」。
                publisher.publishBest();
            } catch (RuntimeException e) {
                log.warn("库存择优发布异常", e);
            }
            LocalDateTime after = entry.getValue().next(now);
            if (after != null) {
                publishNext.put(entry.getKey(), after);
            }
        }
        try {
            auto.retryFailedSlots();
        } catch (RuntimeException e) {
            log.warn("失败槽位补跑扫描异常", e);
        }
    }
}
