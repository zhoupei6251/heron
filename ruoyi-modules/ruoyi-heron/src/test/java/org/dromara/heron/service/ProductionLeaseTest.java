package org.dromara.heron.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionFailureBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.domain.vo.ProductionTaskVo;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.dromara.heron.support.MutableClock;
import org.dromara.heron.support.TestProductionDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("生产租约：心跳、过期回收与围栏令牌")
class ProductionLeaseTest extends HeronMysqlTestSupport {

    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    private MutableClock clock;

    @Autowired
    @Qualifier("newsProductionDomain")
    private TestProductionDomain newsDomain;

    @BeforeEach
    void setUp() {
        resetSchema();
        clearProductionData();
        loginAs(ADMIN);
    }

    @AfterEach
    void resetDomain() {
        newsDomain.setFailLockObjectId(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("心跳延长租约且未过期任务不被回收")
    void heartbeatExtendsLeaseAndFreshLeaseSurvivesRecovery() {
        ProductionRunVo run = submitAndClaim(10);

        LocalDateTime firstLease = leaseUntil(run.taskId());
        clock.advanceSeconds(30);
        assertTrue(executionService.heartbeat(run.taskId(), run.runToken()));
        LocalDateTime secondLease = leaseUntil(run.taskId());
        assertTrue(secondLease.isAfter(firstLease));
        assertFalse(executionService.heartbeat(run.taskId(), "00000000-0000-0000-0000-000000000000"));

        clock.advanceSeconds(10);
        executionService.recoverExpired();
        assertEquals("running", taskStatus(run.taskId()));
        assertEquals(run.runToken(), runToken(run.taskId()));
    }

    @Test
    @DisplayName("过期任务回收为中止态且旧令牌失去全部写入")
    void expiredRunIsRecoveredAndOldTokenLosesWrites() {
        ProductionRunVo run = submitAndClaim(10);
        assertTrue(executionService.beginStage(run, "scripting", 10));

        ProductionTaskVo detail = productionService.queryById(run.taskId());
        assertEquals(1, detail.getSteps().size());
        assertEquals("running", detail.getSteps().getFirst().getStatus());

        clock.advanceSeconds(61);
        executionService.recoverExpired();

        assertEquals("cancelling", taskStatus(run.taskId()));
        String newToken = runToken(run.taskId());
        assertNotEquals(run.runToken(), newToken);
        assertEquals(run.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));

        assertFalse(executionService.heartbeat(run.taskId(), run.runToken()));
        assertFalse(executionService.beginStage(run, "rendering", 20));
        assertFalse(executionService.succeed(run, new ProductionResultVo(List.of(), "{}")));
        assertFalse(executionService.fail(run,
            new ProductionFailureBo("pipeline_error", "scripting", null, "失败", false)));
        assertFalse(executionService.finishCancellation(run, true, false));
        assertEquals("cancelling", taskStatus(run.taskId()));

        ProductionRunVo recovered = new ProductionRunVo(run.taskId(), run.bizType(), run.bizId(),
            run.versionId(), run.attemptNo(), newToken, run.inputJson());
        assertTrue(executionService.finishCancellation(recovered, true, false));
        assertEquals("cancelled", taskStatus(run.taskId()));
        assertEquals("cancelled", jdbc.queryForObject(
            "SELECT status FROM heron_task_step WHERE task_id=?", String.class, run.taskId()));
    }

    @Test
    @DisplayName("再次领取换新令牌，不沿用上一尝试的阶段状态")
    void reclaimedAttemptStartsFromScratch() {
        ProductionRunVo run = submitAndClaim(10);
        assertTrue(executionService.beginStage(run, "scripting", 10));

        jdbc.update("""
            UPDATE heron_production_task
            SET status='queued',run_token=NULL,lease_until=NULL
            WHERE task_id=?
            """, run.taskId());

        ProductionRunVo resumed = executionService.claim(ProductionBizType.news_explainer).orElseThrow();
        assertEquals(2, resumed.attemptNo());
        assertNotEquals(run.runToken(), resumed.runToken());
        // 阶段续跑已删除：新尝试不携带上一尝试的检查点，也不复用它的阶段行
        assertEquals(run.inputJson(), resumed.inputJson());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM heron_task_step WHERE task_id=? AND attempt_no=2", Integer.class, run.taskId()));
    }

    @Test
    @DisplayName("过期回收使用 READ-COMMITTED 且单个任务失败不阻断其余任务")
    void recoveryIsolatesFailuresAndUsesReadCommitted() {
        ProductionRunVo first = submitAndClaim(10);
        insertObject(11, ProductionBizType.news_explainer, ADMIN);
        ProductionReceiptVo secondReceipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_explainer, 11, "lease-key-11", 1));
        LocalDateTime expired = LocalDateTime.now(Clock.systemUTC()).minusSeconds(5);
        jdbc.update("""
            UPDATE heron_production_task
            SET status='running',attempt_no=1,run_token=?,lease_until=?,heartbeat_at=?,started_at=?
            WHERE task_id=?
            """, UUID.randomUUID().toString(), expired, expired, expired, secondReceipt.taskId());

        newsDomain.setFailLockObjectId(11);
        clock.advanceSeconds(61);
        executionService.recoverExpired();

        assertEquals("cancelling", taskStatus(first.taskId()));
        assertEquals("running", taskStatus(secondReceipt.taskId()));
        assertEquals("READ-COMMITTED", newsDomain.lastLockIsolation());
    }

    private ProductionRunVo submitAndClaim(long bizId) {
        insertObject(bizId, ProductionBizType.news_explainer, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_explainer, bizId, "lease-key-" + bizId, 1));
        return executionService.claim(ProductionBizType.news_explainer).orElseThrow();
    }

    private LocalDateTime leaseUntil(long taskId) {
        return jdbc.queryForObject(
            "SELECT lease_until FROM heron_production_task WHERE task_id=?", LocalDateTime.class, taskId);
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
