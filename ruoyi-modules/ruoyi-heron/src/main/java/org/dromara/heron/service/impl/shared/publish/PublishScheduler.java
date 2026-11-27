package org.dromara.heron.service.impl.shared.publish;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronPublishInventory;
import org.dromara.heron.domain.vo.PublishReservationVo;
import org.dromara.heron.service.impl.shared.production.NewsWorkServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.dromara.common.satoken.utils.LoginHelper;

/**
 * 发布调度器：从合格库存里择优一条，原子预占后走既有发布链。
 *
 * <p>与生产调度完全独立——不再「生产完成即发布」，而是库存里当前最值得发的一条。
 * 预占是条件更新，自动与人工共用同一入口，不会重复发布。
 * 不够最低分就空出时段：宁缺毋滥。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishScheduler {

    private final PublishInventoryService inventory;
    private final PublishCuratorService curator;
    private final NewsWorkServiceImpl newsWork;
    private final PublishInventorySync inventorySync;
    private final HeronNewsProperties news;

    @Value("${heron.publish.auto-score-threshold:70}")
    private int scoreThreshold;

    /** 每个发布时段调用一次：先评估新入池条目，再择优发布。 */
    public void publishBest() {
        if (!news.isAutoEnabled() || !news.isAutoConfirmEnabled()) {
            return;
        }
        curator.curatePending();
        HeronPublishInventory top = inventory.topEligible(scoreThreshold);
        if (top == null) {
            log.info("库存无达标条目，本时段空出");
            return;
        }
        publishReserved(top, null);
    }

    /** 人工立即发布：已预占条目直接进发布链（自动/人工共用同一条）。发布记录用真实点击者的身份，不是系统账号。 */
    public PublishReservationVo publishFromInventory(long inventoryId) {
        HeronPublishInventory reserved = inventory.reserve(inventoryId, 0);
        Long actor = currentActor();
        publishReserved(reserved, actor);
        return new PublishReservationVo(reserved.getId(), reserved.getWorkId(), reserved.getVersionId(),
            reserved.getBizLine());
    }

    /** 人工入口有会话就用登录用户；后台定时线程没有会话时走系统账号。 */
    private static Long currentActor() {
        Long userId = null;
        try {
            userId = LoginHelper.getUserId();
        } catch (RuntimeException e) {
            // 无会话上下文（定时线程）：用配置的系统账号
        }
        return userId == null ? null : userId;
    }

    private void publishReserved(HeronPublishInventory reserved, Long actor) {
        inventory.markPublishing(reserved.getId());
        try {
            // 人工入口带真实操作者；自动调度无会话，走系统账号
            newsWork.confirmForAuto(reserved.getWorkId(), reserved.getVersionId(),
                actor != null ? actor : news.getAutoActorId());
            log.info("发布调度器发布 workId={} versionId={} score={}",
                reserved.getWorkId(), reserved.getVersionId(), reserved.getPriorityScore());
        } catch (RuntimeException e) {
            // 发布尝试结果未知时已由账本接管；库存标 UNKNOWN 等人工核对
            inventorySync.onUnknown(reserved.getVersionId());
            log.warn("发布失败，库存条目标 UNKNOWN 待核对: {}", e.getMessage());
            throw e;
        }
    }
}
