package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronPublishInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
        try {
            HeronPublishInventory reserved = inventory.reserve(top.getId(), scoreThreshold);
            newsWork.confirmForAuto(reserved.getWorkId(), reserved.getVersionId(), news.getAutoActorId());
            log.info("发布调度器择优发布 workId={} versionId={} score={}",
                reserved.getWorkId(), reserved.getVersionId(), reserved.getPriorityScore());
        } catch (RuntimeException e) {
            log.warn("择优发布失败，条目留在库存: {}", e.getMessage());
        }
    }
}
