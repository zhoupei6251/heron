package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.enums.PushSourceEnum;
import org.dromara.common.core.enums.PushTypeEnum;
import org.dromara.common.push.helper.PushHelper;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.system.api.domain.PushPayloadDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * 终态后定向通知发起人；推送失败只记脱敏日志，不影响事务与查询。
 */
@Service
@Slf4j
public class ProductionNotificationService {

    private final HeronAccessService access;

    public ProductionNotificationService(HeronAccessService access) {
        this.access = access;
    }

    public void terminal(long taskId, ProductionBizType type, long actorId, long ownerId,
                         ProductionStatus status) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(taskId, type, actorId, ownerId, status);
                }
            });
            return;
        }
        deliver(taskId, type, actorId, ownerId, status);
    }

    private void deliver(long taskId, ProductionBizType type, long actorId, long ownerId,
                         ProductionStatus status) {
        try {
            if (!access.canNotifyActor(actorId, ownerId, type)) {
                return;
            }
            Map<String, Object> data = Map.of(
                "taskId", taskId,
                "bizType", type.name(),
                "status", status.name());
            PushHelper.sendMessage(actorId, PushPayloadDTO.of(
                PushTypeEnum.MESSAGE, PushSourceEnum.BACKEND, message(status), data));
        } catch (RuntimeException e) {
            log.warn("生产任务通知投递失败: taskId={}, status={}", taskId, status);
        }
    }

    private static String message(ProductionStatus status) {
        return switch (status) {
            case succeeded -> "生产任务已完成";
            case failed -> "生产任务失败，可在任务中心查看原因";
            case cancelled -> "生产任务已取消";
            default -> "生产任务状态已更新";
        };
    }
}
