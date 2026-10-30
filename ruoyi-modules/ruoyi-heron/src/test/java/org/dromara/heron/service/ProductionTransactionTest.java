package org.dromara.heron.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionFailureBo;
import org.dromara.heron.domain.bo.ProductionQueryBo;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.domain.vo.ProductionTaskVo;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.dromara.heron.support.TestProductionDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("生产事务：幂等、容量、对象互斥与回滚")
class ProductionTransactionTest extends HeronMysqlTestSupport {

    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;
    private static final long OTHER_USER = 2L;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    @Qualifier("newsProductionDomain")
    private TestProductionDomain newsDomain;

    @BeforeEach
    void setUp() {
        resetSchema();
        clearProductionData();
        loginAs(ADMIN);
    }

    @Test
    @DisplayName("相同请求键重放返回原任务且不重复占版本")
    void sameRequestKeyReplaysOriginalTask() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo first = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionReceiptVo replay = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        assertEquals(first.taskId(), replay.taskId());
        assertEquals(1, count("heron_production_task"));
        assertEquals(1, count("heron_test_version"));
        assertEquals(0, count("heron_usage_record"));
        assertEquals("READ-COMMITTED", newsDomain.lastIsolation());
        assertEquals(first.versionId(), jdbc.queryForObject(
            "SELECT version_id FROM heron_production_task WHERE task_id=?", Long.class, first.taskId()));
    }

    @Test
    @DisplayName("同一请求键对应不同输入返回冲突")
    void sameRequestKeyDifferentInputConflicts() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 2)));

        assertEquals(409, error.getStatus());
        assertEquals("production_request_conflict", error.getCode());
        assertEquals(1, count("heron_production_task"));
    }

    @Test
    @DisplayName("原型容量满 3 后拒绝第 4 个且回滚预留版本")
    void prototypeCapacityRejectsFourthWithoutOrphanVersion() {
        for (long id = 1; id <= 4; id++) {
            insertObject(id, ProductionBizType.prototype, ADMIN);
        }
        ProductionReceiptVo first = null;
        for (long id = 1; id <= 3; id++) {
            ProductionReceiptVo receipt = productionService.submit(
                new ProductionSubmitBo(ProductionBizType.prototype, id, "proto-" + id, 1));
            if (first == null) {
                first = receipt;
            }
        }
        assertEquals(3, count("heron_production_task"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            productionService.submit(new ProductionSubmitBo(ProductionBizType.prototype, 4, "proto-4", 1)));

        assertEquals(409, error.getStatus());
        assertEquals("production_capacity_full", error.getCode());
        assertEquals(3, count("heron_test_version"));
        assertEquals(3, count("heron_production_task"));
        assertEquals(1L, jdbc.queryForObject(
            "SELECT revision FROM heron_test_object WHERE object_id=4", Long.class));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=4", Long.class));

        ProductionReceiptVo replay = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.prototype, 1, "proto-1", 1));
        assertEquals(first.taskId(), replay.taskId());
    }

    @Test
    @DisplayName("同一对象第二个请求键冲突且不产生孤儿版本")
    void sameObjectSecondRequestConflictsWithoutOrphanVersion() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo first = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        HeronBusinessException busy = assertThrows(HeronBusinessException.class, () ->
            productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-b", 1)));
        assertEquals("production_object_busy", busy.getCode());

        loginAs(OTHER_USER);
        assertThrows(HeronAccessException.class, () ->
            productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-d", 1)));
        loginAs(ALL_SCOPE_USER);
        HeronBusinessException otherActorBusy = assertThrows(HeronBusinessException.class, () ->
            productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-d", 1)));
        assertEquals("production_object_busy", otherActorBusy.getCode());
        loginAs(ADMIN);

        insertObject(11, ProductionBizType.news_video, ADMIN);
        HeronBusinessException stale = assertThrows(HeronBusinessException.class, () ->
            productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 11, "key-c", 5)));
        assertEquals("production_revision_conflict", stale.getCode());

        assertEquals(1, count("heron_test_version"));
        assertEquals(1, count("heron_production_task"));
        assertEquals(first.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
        assertEquals(1L, jdbc.queryForObject(
            "SELECT revision FROM heron_test_object WHERE object_id=11", Long.class));
    }

    @Test
    @DisplayName("完成阶段异常的整单回滚保留执行中状态")
    void completeFailureRollsBackToRunning() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertTrue(executionService.beginStage(run, "scripting", 10));

        newsDomain.setFailOnComplete(true);
        assertThrows(IllegalStateException.class, () ->
            executionService.succeed(run, new ProductionResultVo(List.of(), "{\"result\":true}")));

        assertEquals("running", taskStatus(run.taskId()));
        assertEquals(run.runToken(), runToken(run.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT result_json FROM heron_test_version WHERE version_id=?", String.class, run.versionId()));
        assertEquals(run.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));

        newsDomain.setFailOnComplete(false);
        assertTrue(executionService.succeed(run, new ProductionResultVo(List.of(), "{\"result\":true}")));
        assertEquals("succeeded", taskStatus(run.taskId()));
        assertEquals(100, jdbc.queryForObject(
            "SELECT progress FROM heron_production_task WHERE task_id=?", Integer.class, run.taskId()));
        assertNotNull(jdbc.queryForObject(
            "SELECT result_json FROM heron_test_version WHERE version_id=?", String.class, run.versionId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
        assertFalse(executionService.succeed(run, new ProductionResultVo(List.of(), "{}")));
    }

    @Test
    @DisplayName("排队取消释放对象且重复取消幂等")
    void queuedCancellationReleasesObjectAndIsIdempotent() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        ProductionTaskVo cancelled = productionService.cancel(receipt.taskId());
        assertEquals("cancelled", cancelled.getStatus().name());
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));

        ProductionTaskVo again = productionService.cancel(receipt.taskId());
        assertEquals("cancelled", again.getStatus().name());
        assertEquals(1, count("heron_production_task"));
    }

    @Test
    @DisplayName("执行中取消进入中止态并由持有者收尾")
    void runningCancellationFinishesByLeaseHolder() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();

        ProductionTaskVo cancelling = productionService.cancel(receipt.taskId());
        assertEquals("cancelling", cancelling.getStatus().name());
        assertEquals(run.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));

        assertTrue(!executionService.finishCancellation(run, false, false));
        assertEquals("cancelling", taskStatus(run.taskId()));
        assertEquals(run.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
        assertTrue(executionService.finishCancellation(run, true, false));
        assertEquals("cancelled", taskStatus(run.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("外部结果未知的取消按失败终结")
    void unknownExternalOutcomeFailsCancellation() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        productionService.cancel(receipt.taskId());

        assertTrue(executionService.finishCancellation(run, true, true));
        assertEquals("failed", taskStatus(run.taskId()));
        assertEquals("external_outcome_unknown", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE task_id=?", String.class, run.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("失败回执标记外部结果未知时统一错误码并释放占用")
    void externalUnknownFailureUsesCanonicalCodeAndReleasesObject() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertTrue(executionService.beginStage(run, "scripting", 10));

        assertTrue(executionService.fail(run, new ProductionFailureBo(
            "provider_timeout", "scripting", "oss://input/1", "供应商超时", true)));

        assertEquals("failed", taskStatus(run.taskId()));
        assertEquals("external_outcome_unknown", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE task_id=?", String.class, run.taskId()));
        assertEquals("外部结果未知，已按失败处理", jdbc.queryForObject(
            "SELECT error_reason FROM heron_production_task WHERE task_id=?", String.class, run.taskId()));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("同一条生产线同时只允许一个执行者")
    void oneLaneRunsOnlyOneTaskAtATime() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        insertObject(11, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 11, "key-b", 1));

        ProductionRunVo first = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertTrue(executionService.claim(ProductionBizType.news_video).isEmpty());

        executionService.fail(first, new ProductionFailureBo("boom", null, null, "失败", false));
        assertEquals("failed", taskStatus(first.taskId()));
        assertTrue(executionService.claim(ProductionBizType.news_video).isPresent());
    }

    @Test
    @DisplayName("重复开始同一阶段被拒且只留一条阶段记录")
    void duplicateStageIsRejected() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertTrue(executionService.beginStage(run, "scripting", 10));

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            executionService.beginStage(run, "scripting", 20));

        assertEquals(409, error.getStatus());
        assertEquals("production_step_exists", error.getCode());
        assertEquals(1, count("heron_task_step"));
    }

    @Test
    @DisplayName("非所有者取消他人任务返回访问拒绝且状态不变")
    void nonOwnerCannotCancelTask() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        loginAs(OTHER_USER);
        assertThrows(HeronAccessException.class, () -> productionService.cancel(receipt.taskId()));
        loginAs(ADMIN);

        assertEquals("queued", taskStatus(receipt.taskId()));
        assertEquals(receipt.taskId(), jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("超长失败诊断按列宽截断且不拆分代理对")
    void oversizedFailureDiagnosticsAreBounded() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();
        assertTrue(executionService.beginStage(run, "scripting", 10));

        assertTrue(executionService.fail(run, new ProductionFailureBo(
            "c".repeat(70), "scripting", "l".repeat(300), "🙂".repeat(2500), false)));

        assertEquals(64, jdbc.queryForObject(
            "SELECT CHAR_LENGTH(error_code) FROM heron_production_task WHERE task_id=?",
            Integer.class, run.taskId()));
        assertEquals(2000, jdbc.queryForObject(
            "SELECT CHAR_LENGTH(error_reason) FROM heron_production_task WHERE task_id=?",
            Integer.class, run.taskId()));
        assertEquals(256, jdbc.queryForObject(
            "SELECT CHAR_LENGTH(input_locator) FROM heron_task_step WHERE task_id=? AND stage='scripting'",
            Integer.class, run.taskId()));
    }

    @RepeatedTest(20)
    @DisplayName("取消与成功竞争只有一方终结（重复 20 轮）")
    void cancelAndSucceedRaceHasSingleTerminalOutcome() throws Exception {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));
        ProductionRunVo run = executionService.claim(ProductionBizType.news_video).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> cancelFuture = pool.submit(() -> {
                loginAs(ADMIN);
                start.await(5, TimeUnit.SECONDS);
                return productionService.cancel(receipt.taskId());
            });
            Future<Boolean> succeedFuture = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return executionService.succeed(run, new ProductionResultVo(List.of(), "{\"race\":true}"));
            });
            start.countDown();
            cancelFuture.get(10, TimeUnit.SECONDS);
            succeedFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String status = taskStatus(run.taskId());
        String result = jdbc.queryForObject(
            "SELECT result_json FROM heron_test_version WHERE version_id=?", String.class, run.versionId());
        Long activeTask = jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class);
        if ("succeeded".equals(status)) {
            assertNotNull(result);
            assertNull(activeTask);
        } else {
            assertEquals("cancelling", status);
            assertNull(result);
            assertEquals(run.taskId(), activeTask);
        }
    }

    @RepeatedTest(20)
    @DisplayName("并发领取只有唯一持有者（重复 20 轮）")
    void concurrentClaimHasSingleWinner() throws Exception {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        productionService.submit(new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<ProductionRunVo>> left = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return executionService.claim(ProductionBizType.news_video);
            });
            Future<Optional<ProductionRunVo>> right = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return executionService.claim(ProductionBizType.news_video);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long winners = (left.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                + (right.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM heron_production_task WHERE status='running'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("非所有者看不到他人任务")
    void nonOwnerCannotReadTask() {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, "key-a", 1));

        loginAs(OTHER_USER);
        assertThrows(HeronAccessException.class, () -> productionService.queryById(receipt.taskId()));
        assertEquals(0, productionService.queryPageList(new ProductionQueryBo(), new PageQuery())
            .getRows().size());

        loginAs(ADMIN);
        assertEquals(1, productionService.queryPageList(new ProductionQueryBo(), new PageQuery())
            .getRows().size());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
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
