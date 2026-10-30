package org.dromara.heron.domain;

/**
 * 生产任务执行状态，业务审核状态不在此建模。
 */
public enum ProductionStatus {
    queued,
    running,
    cancelling,
    succeeded,
    failed,
    cancelled
}
