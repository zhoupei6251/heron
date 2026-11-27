package org.dromara.heron.service.impl.shared.research;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.service.IResearchTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 研究任务的数据库驱动调度。
 *
 * <p>为什么必须要有它：研究任务的状态、租约、心跳都在库里，但执行入口原本只有 HTTP 触发。
 * 服务重启后，库里排队中的任务没人会再点一次；执行方崩掉后，running 行要等租约过期
 * 还得靠外部再触发才会重来。把状态存进数据库却没人扫描，等于把持久化队列当装饰。
 *
 * <p>写法和 {@link ProductionRunner} 一致：共用基座的 {@code scheduledExecutorService}，
 * 一拍做两件事——收尾失联的取消、捞出可领取的任务。
 * 真正的领取仍然由 {@link ResearchTaskServiceImpl#run} 的条件更新竞争决定，
 * 这里只是「把候选递过去」，并发安全不依赖调度器。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "heron.agent", name = "enabled", havingValue = "true")
public class ResearchScheduler {

    /** 轮询间隔：研究是分钟级长任务，30 秒足够把重启后遗留的任务捞回来。 */
    static final int POLL_SECONDS = 30;
    /** 一拍最多领多少条：执行位只有 2 个，领多了也只是排进有界队列。 */
    public static final int BATCH = 4;

    private final ObjectProvider<IResearchTaskService> tasks;
    private final ScheduledExecutorService scheduler;

    public ResearchScheduler(ObjectProvider<IResearchTaskService> tasks,
                             @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        this.tasks = tasks;
        this.scheduler = scheduler;
    }

    /** 注册每 30 秒一拍：先收尾失联的取消，再把可领取任务递给执行位；真正的领取竞争在 {@code run} 里。 */
    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("Heron 研究调度器已启用");
    }

    public void tick() {
        IResearchTaskService service = tasks.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int resolved = service.resolveAbandonedCancels();
            if (resolved > 0) {
                log.info("研究取消已收尾 {} 条：执行方已失联", resolved);
            }
        } catch (RuntimeException e) {
            log.warn("研究取消失联收尾失败", e);
        }
        try {
            List<Long> ids = service.claimableIds(BATCH);
            for (Long id : ids) {
                service.trigger(id);
            }
        } catch (RuntimeException e) {
            log.warn("研究任务扫描领取失败", e);
        }
    }
}
