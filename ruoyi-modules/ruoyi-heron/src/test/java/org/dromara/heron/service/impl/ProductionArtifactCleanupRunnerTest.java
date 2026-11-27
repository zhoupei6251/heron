package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.production.ProductionArtifactCleanupRunner;
import org.dromara.heron.service.impl.shared.production.ProductionArtifactService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("云端孤儿元数据清理调度：15 天到期清理被驱动，失败不取消调度")
class ProductionArtifactCleanupRunnerTest {

    @Test
    @DisplayName("tick 驱动 15 天云端孤儿元数据清理")
    void tickRunsCloudCleanup() {
        ProductionArtifactService artifacts = mock(ProductionArtifactService.class);

        new ProductionArtifactCleanupRunner(artifacts, null).tick();

        verify(artifacts).cleanupExpiredUploads(Duration.ofDays(15));
    }

    @Test
    @DisplayName("清理抛异常时 tick 吞掉，固定延时调度不会被取消")
    void tickSwallowsFailure() {
        ProductionArtifactService artifacts = mock(ProductionArtifactService.class);
        when(artifacts.cleanupExpiredUploads(Duration.ofDays(15))).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> new ProductionArtifactCleanupRunner(artifacts, null).tick());
    }
}
