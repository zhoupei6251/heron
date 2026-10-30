package org.dromara.heron.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("外部调用账本：一次完成与未知用量")
class ProductionUsageTest extends HeronMysqlTestSupport {

    private static final long ADMIN = SystemConstants.SUPER_ADMIN_USER_ID;

    @Autowired
    private IProductionService productionService;

    @Autowired
    private IProductionExecutionService executionService;

    @Autowired
    private ProductionUsageService usageService;

    @BeforeEach
    void setUp() {
        resetSchema();
        clearProductionData();
        loginAs(ADMIN);
    }

    @Test
    @DisplayName("同一 callId 最多完成一次，重复开始被拒绝")
    void sameCallCompletesOnlyOnce() {
        ProductionRunVo run = submitAndClaim("usage-1");
        String callId = UUID.randomUUID().toString();

        assertTrue(usageService.start(run, "tts", callId, "volc", "doubao-tts"));
        assertFalse(usageService.start(run, "tts", callId, "volc", "doubao-tts"));
        assertTrue(usageService.complete(run, callId, "provider-call-1", 120L, 340L, 900));
        assertFalse(usageService.complete(run, callId, "provider-call-2", 1L, 2L, 100));

        assertEquals("succeeded", jdbc.queryForObject(
            "SELECT status FROM heron_usage_record WHERE task_id=? AND call_id=?",
            String.class, run.taskId(), callId));
        assertEquals("provider-call-1", jdbc.queryForObject(
            "SELECT provider_call_id FROM heron_usage_record WHERE task_id=? AND call_id=?",
            String.class, run.taskId(), callId));
        assertEquals(120L, jdbc.queryForObject(
            "SELECT input_tokens FROM heron_usage_record WHERE task_id=? AND call_id=?",
            Long.class, run.taskId(), callId));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM heron_usage_record WHERE task_id=?", Integer.class, run.taskId()));
    }

    @Test
    @DisplayName("突然退出把 started 标记为 unknown 且 token 保持 null")
    void abandonedCallStaysUnknownWithNullTokens() {
        ProductionRunVo run = submitAndClaim("usage-2");
        String callId = UUID.randomUUID().toString();
        assertTrue(usageService.start(run, "script", callId, "volc", "doubao-pro"));
        productionService.cancel(run.taskId());

        assertEquals(1, usageService.markAttemptUnknown(run));
        assertEquals("unknown", jdbc.queryForObject(
            "SELECT status FROM heron_usage_record WHERE task_id=? AND call_id=?",
            String.class, run.taskId(), callId));
        assertNull(jdbc.queryForObject(
            "SELECT input_tokens FROM heron_usage_record WHERE task_id=? AND call_id=?",
            Long.class, run.taskId(), callId));
        assertNull(jdbc.queryForObject(
            "SELECT output_tokens FROM heron_usage_record WHERE task_id=? AND call_id=?",
            Long.class, run.taskId(), callId));
        assertFalse(usageService.complete(run, callId, null, 1L, 1L, 10));
    }

    @Test
    @DisplayName("用量参数在写入前校验")
    void rejectsInvalidUsageParameters() {
        ProductionRunVo run = submitAndClaim("usage-3");
        assertThrows(IllegalArgumentException.class, () ->
            usageService.start(run, "tts", "x".repeat(37), null, null));
        assertThrows(IllegalArgumentException.class, () ->
            usageService.complete(run, UUID.randomUUID().toString(), null, 1L, 1L, -1));
    }

    private ProductionRunVo submitAndClaim(String key) {
        insertObject(10, ProductionBizType.news_video, ADMIN);
        ProductionReceiptVo receipt = productionService.submit(
            new ProductionSubmitBo(ProductionBizType.news_video, 10, key, 1));
        return executionService.claim(ProductionBizType.news_video).orElseThrow();
    }
}
