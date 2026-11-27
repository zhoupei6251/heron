package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronPublishInventory;
import org.dromara.heron.mapper.HeronPublishInventoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 库存状态与发布事实的同步点。
 *
 * <p>发布回执落库（applyReceipt）与未知结果标记时调用；同步失败只记日志，
 * 不阻断发布主链——发布事实已落库，库存状态可由对账修复，反之丢发布不可接受。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishInventorySync {

    private final HeronPublishInventoryMapper inventoryMapper;

    /** 该版本已确认发布：本条 PUBLISHED，同作品其余未发布条目全部 SUPERSEDED。 */
    public void onPublished(long versionId) {
        HeronPublishInventory row = inventoryMapper.selectOne(inventoryMapper.lambda()
            .eq(HeronPublishInventory::getVersionId, versionId).build());
        if (row == null) {
            // 老版本或旁路发布没有库存条目：没有事实可同步
            return;
        }
        try {
            inventoryMapper.lambda()
                .set(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_PUBLISHED)
                .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
                .eq(HeronPublishInventory::getId, row.getId())
                .in(HeronPublishInventory::getPublishStatus,
                    HeronPublishInventory.PUBLISH_WAITING, HeronPublishInventory.PUBLISH_RESERVED,
                    HeronPublishInventory.PUBLISH_PUBLISHING, HeronPublishInventory.PUBLISH_UNKNOWN)
                .update();
            inventoryMapper.lambda()
                .set(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_SUPERSEDED)
                .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
                .eq(HeronPublishInventory::getWorkId, row.getWorkId())
                .ne(HeronPublishInventory::getId, row.getId())
                .in(HeronPublishInventory::getPublishStatus,
                    HeronPublishInventory.PUBLISH_WAITING, HeronPublishInventory.PUBLISH_RESERVED)
                .update();
        } catch (RuntimeException e) {
            log.warn("库存发布状态同步失败 versionId={}：{}", versionId, e.toString());
        }
    }

    /** 发布尝试结果未知：本条标 UNKNOWN（覆盖 RESERVED 与 PUBLISHING——发布中抛异常时已是 PUBLISHING），等待人工核对后由 onPublished 或 onWithdrawn 收尾。 */
    public void onUnknown(long versionId) {
        try {
            inventoryMapper.lambda()
                .set(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_UNKNOWN)
                .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
                .eq(HeronPublishInventory::getVersionId, versionId)
                .in(HeronPublishInventory::getPublishStatus,
                    HeronPublishInventory.PUBLISH_RESERVED, HeronPublishInventory.PUBLISH_PUBLISHING)
                .update();
        } catch (RuntimeException e) {
            log.warn("库存未知状态同步失败 versionId={}：{}", versionId, e.toString());
        }
    }
}
