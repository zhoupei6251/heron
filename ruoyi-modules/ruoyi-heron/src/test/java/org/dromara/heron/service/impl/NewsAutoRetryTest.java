package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补跑只对「失败」开口：产出的重跑会重复出片，跳过的空槽是有意的决定。
 */
@DisplayName("失败槽位补跑：只补 failed，且只补一次")
class NewsAutoRetryTest {

    @Test
    void onlyFailedSlotsWithBudgetAreRetried() {
        assertTrue(NewsAutoService.retryable("failed", null), "老数据没有补跑计数，视为没补过");
        assertTrue(NewsAutoService.retryable("failed", 0));
        assertFalse(NewsAutoService.retryable("failed", 1), "额度用完就不再补");
        assertFalse(NewsAutoService.retryable("produced", 0), "产出的重跑会重复出片");
        assertFalse(NewsAutoService.retryable("skipped", 0), "空槽是有意的决定，不是故障");
        assertFalse(NewsAutoService.retryable("running", 0));
    }
}
