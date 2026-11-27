package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.production.NewsCleanupRunner;
import org.dromara.heron.service.impl.shared.production.NewsWorkServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("成片清理调度：到期清理被驱动，失败不取消调度")
class NewsCleanupRunnerTest {

    @Test
    @DisplayName("tick 驱动真实清理（非 dryRun）")
    void tickRunsCleanup() {
        NewsWorkServiceImpl maintenance = mock(NewsWorkServiceImpl.class);
        new NewsCleanupRunner(maintenance, null).tick();
        verify(maintenance).cleanupExpired(false);
    }

    @Test
    @DisplayName("清理抛异常时 tick 吞掉，固定延时调度不会被取消")
    void tickSwallowsFailure() {
        NewsWorkServiceImpl maintenance = mock(NewsWorkServiceImpl.class);
        when(maintenance.cleanupExpired(false)).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> new NewsCleanupRunner(maintenance, null).tick());
    }
}
