package org.dromara.heron.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.dromara.heron.service.impl.ProductionRecoveryService;
import org.dromara.heron.service.impl.ProductionRunner;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.dromara.heron.support.TestProductionPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("生产 worker：领取、失败映射与取消收尾")
class ProductionWorkerTest extends HeronMysqlTestSupport {

    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    private ProductionProcessService processes;

    @Autowired
    private ProductionRecoveryService recoveryService;

    @Autowired
    private HeronProductionProperties properties;

    @Autowired
    private TestProductionPipeline pipeline;

    @Autowired
    private ProductionUsageService usageService;

    private ScheduledExecutorService scheduler;
    private ProductionRunner runner;

    @BeforeEach
    void setUp() {
        resetSchema();
        clearProductionData();
        pipeline.reset();
        loginAs(ADMIN);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        runner = new ProductionRunner(context, executionService, processes, recoveryService,
            usageService, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        pipeline.reset();
    }

    @Test
    @DisplayName("成功流水线终结任务并写入业务结果")
    void successfulPipelineCompletesTask() {
        submit(10, ProductionBizType.news_video);

        assertTrue(runner.runOnce(ProductionBizType.news_video));

        assertEquals("succeeded", taskStatus(10));
        assertEquals("true", jdbc.queryForObject(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.result')) FROM heron_test_version WHERE object_id=10",
            String.class));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("流水线业务异常按自身错误码失败并释放对象")
    void pipelineFailureKeepsCodeAndReleasesObject() {
        submit(10, ProductionBizType.news_video);
        pipeline.setAction(run -> {
            throw new HeronBusinessException(500, "tts_failed", "TTS 不可用");
        });

        assertTrue(runner.runOnce(ProductionBizType.news_video));

        assertEquals("failed", taskStatus(10));
        assertEquals("tts_failed", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE biz_id=10", String.class));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("流水线内被取消时按取消终结，不覆盖为成功")
    void cancellationDuringPipelineEndsCancelled() {
        submit(10, ProductionBizType.news_video);
        pipeline.setAction(run -> {
            productionService.cancel(run.taskId());
            return new ProductionResultVo(List.of(), "{\"late\":true}");
        });

        assertTrue(runner.runOnce(ProductionBizType.news_video));

        assertEquals("cancelled", taskStatus(10));
        assertNull(jdbc.queryForObject(
            "SELECT result_json FROM heron_test_version WHERE object_id=10", String.class));
        assertNull(jdbc.queryForObject(
            "SELECT active_task FROM heron_test_object WHERE object_id=10", Long.class));
    }

    @Test
    @DisplayName("外部调用开始后流水线失败按结果未知记录")
    void startedUsageTurnsPipelineFailureIntoUnknownOutcome() {
        submit(10, ProductionBizType.news_video);
        pipeline.setAction(run -> {
            usageService.start(run, "tts", UUID.randomUUID().toString(), "volc", "doubao-tts");
            throw new HeronBusinessException(500, "tts_failed", "TTS 不可用");
        });

        assertTrue(runner.runOnce(ProductionBizType.news_video));

        assertEquals("failed", taskStatus(10));
        assertEquals("external_outcome_unknown", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE biz_id=10", String.class));
        assertEquals("unknown", jdbc.queryForObject(
            "SELECT status FROM heron_usage_record WHERE task_id=("
                + "SELECT task_id FROM heron_production_task WHERE biz_id=10)", String.class));
    }

    @Test
    @DisplayName("产物缺失时成功落库被拒绝并转为失败")
    void missingArtifactRejectsSuccess() {
        submit(10, ProductionBizType.news_video);
        pipeline.setAction(run -> new ProductionResultVo(List.of(new ProductionArtifactVo(
            UUID.randomUUID().toString(), "video", "videos/10/v1/missing.mp4", 10,
            "0".repeat(64), "video/mp4")), "{\"result\":true}"));

        assertTrue(runner.runOnce(ProductionBizType.news_video));

        assertEquals("failed", taskStatus(10));
        assertEquals("production_file_missing", jdbc.queryForObject(
            "SELECT error_code FROM heron_production_task WHERE biz_id=10", String.class));
    }

    @Test
    @DisplayName("缺少流水线实现时不领取任务")
    void missingPipelineDoesNotClaim() {
        submit(20, ProductionBizType.prototype);

        assertFalse(runner.runOnce(ProductionBizType.prototype));
        assertEquals("queued", jdbc.queryForObject(
            "SELECT status FROM heron_production_task WHERE biz_id=20", String.class));
    }

    private void submit(long bizId, ProductionBizType type) {
        insertObject(bizId, type, ADMIN);
        productionService.submit(new ProductionSubmitBo(type, bizId, "worker-" + bizId, 1));
    }

    private String taskStatus(long bizId) {
        return jdbc.queryForObject(
            "SELECT status FROM heron_production_task WHERE biz_id=?", String.class, bizId);
    }
}
