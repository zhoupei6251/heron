package org.dromara.heron.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionFailureBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionCleanupService;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.dromara.heron.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("清理 dry-run：只列安全候选")
class ProductionCleanupTest extends HeronMysqlTestSupport {

    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    private ProductionCleanupService cleanupService;

    @Autowired
    private HeronProductionProperties properties;

    @Autowired
    private MutableClock clock;

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        resetSchema();
        clearProductionData();
        loginAs(ADMIN);
        root = properties.getDataRoot().toAbsolutePath().normalize();
        deleteRecursively(root);
        Files.createDirectories(root);
    }

    @Test
    @DisplayName("只列已取消新闻任务的过期尝试，跳过失败、原型与保留证据")
    void listsOnlyExpiredCancelledNewsAttempts() throws IOException {
        Instant old = clock.instant().minus(Duration.ofDays(8));

        long cancelledTask = cancelledNewsTask(10);
        Path candidate = attemptDir("news_video", cancelledTask, old);

        long failedTask = failedNewsTask(11);
        attemptDir("news_video", failedTask, old);

        long keptTask = cancelledNewsTask(12);
        Path kept = attemptDir("news_video", keptTask, old);
        Files.writeString(kept.resolve(".keep"), "evidence");

        long recentTask = cancelledNewsTask(13);
        attemptDir("news_video", recentTask, clock.instant());

        long prototypeTask = cancelledPrototypeTask(30);
        attemptDir("prototype", prototypeTask, old);

        List<String> candidates = cleanupService.dryRun(Duration.ofDays(7));

        assertEquals(1, candidates.size());
        assertEquals(root.relativize(candidate).toString().replace('\\', '/'), candidates.getFirst());
        assertTrue(Files.isDirectory(candidate));
        assertTrue(Files.isDirectory(kept));
    }

    private Path attemptDir(String lane, long taskId, Instant modified) throws IOException {
        Path directory = root.resolve("work").resolve(lane)
            .resolve(Long.toString(taskId)).resolve("a1-" + UUID.randomUUID());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("intermediate.txt"), "x");
        Files.setLastModifiedTime(directory, FileTime.from(modified));
        return directory;
    }

    private long cancelledNewsTask(long bizId) {
        insertObject(bizId, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, bizId, "cleanup-" + bizId, 1));
        productionService.cancel(receipt.taskId());
        return receipt.taskId();
    }

    private long failedNewsTask(long bizId) {
        insertObject(bizId, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, bizId, "cleanup-" + bizId, 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        executionService.fail(run, new ProductionFailureBo("test_failed", null, null, "测试失败", false));
        return run.taskId();
    }

    private long cancelledPrototypeTask(long bizId) {
        insertObject(bizId, ProductionBizType.prototype, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.prototype, bizId, "cleanup-" + bizId, 1));
        productionService.cancel(receipt.taskId());
        return receipt.taskId();
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
