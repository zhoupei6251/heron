package org.dromara.heron.service;

import org.dromara.heron.domain.FrozenContext;
import org.dromara.heron.domain.vo.NewsWorkVo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.service.impl.FrozenContextService;
import org.dromara.heron.domain.bo.NewsGenerateBo;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冻结上下文落库口径：一次生产一行、只插入不更新、版本之间用 parent 串起来。
 * 需要真实 MySQL：json 列的规范化行为是本用例要覆盖的事实之一。
 */
@DisplayName("冻结上下文：落库与版本链")
class ProductionContextTest extends NewsMysqlTestSupport {

    @Autowired private FrozenContextService contexts;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    @DisplayName("提交即冻结：hash 与任务输入一致，序号与版本同源，首版无父")
    void reserveFreezesContext() {
        NewsWorkVo work = work();
        allowProduction();
        ProductionReceiptVo receipt = news.generate(work.getWorkId(),
            new NewsGenerateBo("ctx-first", work.getRevision()));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT content_hash, content_json, context_no, parent_context_id, schema_version, revision_reason"
                + " FROM heron_production_context WHERE task_id=?", receipt.taskId());
        String snapshot = jdbc.queryForObject(
            "SELECT input_snapshot FROM heron_production_task WHERE task_id=?", String.class, receipt.taskId());

        // json 列读回来会被 MySQL 重新规范化，所以比的是「解析后再规范化」的摘要，不是原字符串
        assertEquals(FrozenContext.hash(snapshot), row.get("content_hash"));
        assertEquals(FrozenContext.SCHEMA, row.get("schema_version"));
        assertEquals(1, ((Number) row.get("context_no")).intValue());
        assertNull(row.get("parent_context_id"));
        assertEquals("reserve", row.get("revision_reason"));
        assertNotNull(row.get("content_json"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM heron_production_context", Integer.class));
    }

    @Test
    @DisplayName("同一作品的第二份上下文指向上一份，且互不覆盖")
    void secondContextLinksToParent() {
        NewsWorkVo work = work();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        FrozenContext first = tx.execute(status ->
            contexts.freeze(work.getWorkId(), 9001L, 8001L, 1, OWNER, "{\"kind\":\"data_board\",\"v\":1}"));
        FrozenContext second = tx.execute(status ->
            contexts.freeze(work.getWorkId(), 9002L, 8002L, 2, OWNER, "{\"kind\":\"data_board\",\"v\":2}"));

        assertNotNull(first);
        assertNotNull(second);
        assertNull(first.parentContextId());
        assertEquals(first.contextId(), second.parentContextId());
        assertNotEquals(first.contentHash(), second.contentHash());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM heron_production_context", Integer.class));
        // 旧行原样保留：改内容只能新建版本
        assertEquals(first.contentHash(), jdbc.queryForObject(
            "SELECT content_hash FROM heron_production_context WHERE context_id=?", String.class, first.contextId()));
    }

    @Test
    @DisplayName("执行前校验：库里内容被改过就拒绝，未改过则放行")
    void verificationCatchesTamperedSnapshot() {
        NewsWorkVo work = work();
        allowProduction();
        ProductionReceiptVo receipt = news.generate(work.getWorkId(),
            new NewsGenerateBo("ctx-verify", work.getRevision()));
        String snapshot = jdbc.queryForObject(
            "SELECT input_snapshot FROM heron_production_task WHERE task_id=?", String.class, receipt.taskId());

        assertEquals(64, contexts.verify(receipt.taskId(), snapshot).contentHash().length());

        String tampered = snapshot.replace("\"data_board\"", "\"article\"");
        var error = assertThrows(org.dromara.heron.domain.HeronBusinessException.class,
            () -> contexts.verify(receipt.taskId(), tampered));
        assertEquals("news_context_mismatch", error.getCode());
    }
}
