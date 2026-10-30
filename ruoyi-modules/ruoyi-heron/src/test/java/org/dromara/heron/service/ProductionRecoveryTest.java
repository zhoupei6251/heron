package org.dromara.heron.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionRecoveryService;
import org.dromara.heron.service.impl.ProductionRuntimeIdentityService;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.dromara.heron.support.MutableClock;
import org.dromara.heron.support.TestProductionPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("恢复协调：身份确认、重排与终结")
class ProductionRecoveryTest extends HeronMysqlTestSupport {

    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    private ProductionRecoveryService recoveryService;

    @Autowired
    private ProductionUsageService usageService;

    @Autowired
    private ProductionRuntimeIdentityService identities;

    @Autowired
    private TestProductionPipeline pipeline;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        resetSchema();
        clearProductionData();
        pipeline.reset();
        loginAs(ADMIN);
    }

    @AfterEach
    void resetPipeline() {
        pipeline.reset();
    }

    @Test
    @DisplayName("过期任务在流水线确认可续跑时回到队列且保留版本")
    void expiredRunRequeuesWithoutNewVersionWhenPolicyAllows() {
        ProductionRunVo first = submitAndClaim("recover-1");
        pipeline.setResume(true);

        clock.advanceSeconds(61);
        executionService.recoverExpired();
        assertEquals("cancelling", taskStatus(first.taskId()));
        assertNotEquals(first.runToken(), runToken(first.taskId()));

        assertEquals(1, recoveryService.recoverCancelling());
        assertEquals("queued", taskStatus(first.taskId()));
        assertNull(runToken(first.taskId()));
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempt_no FROM heron_production_task WHERE task_id=?", Integer.class, first.taskId()));
        assertEquals(first.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM heron_test_version WHERE object_id=10", Integer.class));

        ProductionRunVo second = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertEquals(2, second.attemptNo());
        assertNotEquals(first.runToken(), second.runToken());
    }

    @Test
    @DisplayName("流水线无法确认续跑时终结并释放对象")
    void expiredRunFailsWhenPolicyRejectsResume() {
        ProductionRunVo first = submitAndClaim("recover-2");
        pipeline.setResume(false);

        clock.advanceSeconds(61);
        executionService.recoverExpired();
        assertEquals(1, recoveryService.recoverCancelling());

        assertEquals("failed", taskStatus(first.taskId()));
        assertEquals("production_recovery_blocked", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE task_id=?", String.class, first.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
        assertNull(jdbc.queryForObject(
            "SELECT result_json FROM heron_test_version WHERE version_id=?", String.class, first.versionId()));
    }

    @Test
    @DisplayName("用户取消在租约过期后按取消终结")
    void userCancellationFinishesAsCancelled() {
        ProductionRunVo run = submitAndClaim("recover-3");
        productionService.cancel(run.taskId());
        assertEquals("cancelling", taskStatus(run.taskId()));

        clock.advanceSeconds(61);
        assertEquals(1, recoveryService.recoverCancelling());

        assertEquals("cancelled", taskStatus(run.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("缺少运行身份说明没有本地进程，按外部结果未知终结")
    void startedStageWithoutIdentityFinishesAsUnknown() {
        ProductionRunVo run = submitAndClaim("recover-4");
        assertTrue(executionService.beginStage(run, "tts", 10));
        assertTrue(usageService.start(run, "tts", UUID.randomUUID().toString(), "volc", "doubao-tts"));

        clock.advanceSeconds(61);
        executionService.recoverExpired();
        assertEquals("cancelling", taskStatus(run.taskId()));
        assertEquals(1, recoveryService.recoverCancelling());

        assertEquals("failed", taskStatus(run.taskId()));
        assertEquals("external_outcome_unknown", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE task_id=?", String.class, run.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
        assertEquals("unknown", jdbc.queryForObject(
            "SELECT status FROM heron_usage_record WHERE task_id=?", String.class, run.taskId()));
    }

    @Test
    @DisplayName("旧尝试令牌不能终结恢复后的新版本")
    void staleAttemptCannotFinalizeCurrentVersion() {
        ProductionRunVo first = submitAndClaim("recover-5");
        pipeline.setResume(true);
        clock.advanceSeconds(61);
        executionService.recoverExpired();
        assertEquals(1, recoveryService.recoverCancelling());

        ProductionRunVo second = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertNotEquals(first.runToken(), second.runToken());
        assertFalse(executionService.succeed(first, result()));
        assertTrue(executionService.succeed(second, result()));

        assertEquals("succeeded", taskStatus(first.taskId()));
        assertNotNull(jdbc.queryForObject(
            "SELECT result_json FROM heron_test_version WHERE version_id=?", String.class, first.versionId()));
        assertEquals(second.runToken(), runToken(first.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("运行身份只落在执行中的步骤")
    void runtimeIdentityPersistsOnRunningStep() {
        ProductionRunVo run = submitAndClaim("recover-6");
        assertTrue(executionService.beginStage(run, "rendering", 30));

        String identity = "{\"guardPid\":4242,\"pgid\":4242,\"startTicks\":99,\"bootId\":\"test\"}";
        assertTrue(identities.record(run, identity));
        String stored = identities.latest(run.taskId(), run.attemptNo());
        assertNotNull(stored);
        assertTrue(stored.contains("\"guardPid\"") && stored.contains("4242"));
        assertEquals(stored, jdbc.queryForObject(
            "SELECT runtime_identity FROM heron_task_step WHERE task_id=? AND stage='rendering'",
            String.class, run.taskId()));

        assertFalse(identities.record(run, "not-json".repeat(600)));
    }

    @Test
    @DisplayName("身份损坏时取消收尾不改终态")
    void brokenIdentityBlocksImmediateCancellation() {
        ProductionRunVo run = submitAndClaim("recover-7");
        assertTrue(executionService.beginStage(run, "scripting", 10));
        assertTrue(identities.record(run, "{\"guardPid\":0}"));
        productionService.cancel(run.taskId());

        assertFalse(recoveryService.finishCancelledRun(run));
        assertEquals("cancelling", taskStatus(run.taskId()));
        assertEquals(run.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    private ProductionRunVo submitAndClaim(String key) {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, key, 1));
        return executionService.claim(ProductionBizType.news_video).orElseThrow();
    }

    private ProductionResultVo result() {
        return new ProductionResultVo(List.of(), "{\"result\":true}");
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject(
            "SELECT status FROM heron_production_task WHERE task_id=?", String.class, taskId);
    }

    private String runToken(long taskId) {
        return jdbc.queryForObject(
            "SELECT run_token FROM heron_production_task WHERE task_id=?", String.class, taskId);
    }
}
