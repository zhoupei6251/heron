package org.dromara.heron.service.impl;

import org.dromara.heron.domain.FrozenContext;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionContext;
import org.dromara.heron.mapper.HeronProductionContextMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 执行前校验：有上下文行的任务必须与冻结内容吻合；没有行的是迁移前的老任务。
 * 这一层是「拿被改过的输入去生产」的唯一拦截点。
 */
@DisplayName("冻结上下文：执行前校验")
class FrozenContextServiceTest {

    private static final String CONTENT = "{\"kind\":\"data_board\",\"schemaVersion\":\"1.0\"}";

    private static HeronProductionContext row(String content, String schema) {
        HeronProductionContext row = new HeronProductionContext();
        row.setContextId(1L);
        row.setWorkId(10L);
        row.setVersionId(20L);
        row.setTaskId(30L);
        row.setContextNo(1);
        row.setSchemaVersion(schema);
        row.setContentHash(FrozenContext.hash(content));
        row.setContentJson(content);
        return row;
    }

    private static FrozenContextService service(HeronProductionContextMapper mapper) {
        return new FrozenContextService(mapper, null);
    }

    @Test
    @DisplayName("没有上下文行：按 legacy 放行，不抛错")
    void legacyTaskPassesThrough() {
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);

        assertNull(service(mapper).verify(30L, CONTENT));
    }

    @Test
    @DisplayName("任务 ID 为 0 的纯决策调用不查库")
    void nonTaskCallerSkipsLookup() {
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);

        assertNull(service(mapper).verify(0L, CONTENT));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("内容一致：返回上下文")
    void matchingContentPasses() {
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);
        when(mapper.selectOne(any())).thenReturn(row(CONTENT, FrozenContext.SCHEMA));

        FrozenContext context = service(mapper).verify(30L, CONTENT);

        assertNotNull(context);
        assertEquals(1L, context.contextId());
        assertEquals(FrozenContext.hash(CONTENT), context.contentHash());
    }

    @Test
    @DisplayName("输入被改过：拒绝执行")
    void tamperedInputIsRejected() {
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);
        when(mapper.selectOne(any())).thenReturn(row(CONTENT, FrozenContext.SCHEMA));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service(mapper).verify(30L, "{\"kind\":\"article\",\"schemaVersion\":\"1.0\"}"));

        assertEquals(409, error.getStatus());
        assertEquals("news_context_mismatch", error.getCode());
    }

    @Test
    @DisplayName("上下文版本不认识：拒绝，不猜")
    void unknownSchemaIsRejected() {
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);
        when(mapper.selectOne(any())).thenReturn(row(CONTENT, "9.9"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service(mapper).verify(30L, CONTENT));

        assertEquals("news_context_schema", error.getCode());
    }

    /**
     * 回归：库里的小数是按 double 存的，读回时打印的是最短 double 表示。
     *
     * <p>真实故障：机会信号里的 {@code magnitude} 写入时是 {@code 0.9170061196527047}，
     * MySQL 的 json 列读回来是 {@code 0.9170061196527048}。行里存的摘要仍是写入时算的那个，
     * 于是「拿写入时的摘要比读回后的内容」必然对不上，没被改过的输入也被判成「不一致」，
     * 整条自动产出线全部失败。
     */
    @Test
    @DisplayName("库里的小数按 double 归一化过：内容没被改过就不能判成不一致")
    void normalizedDecimalDoesNotLookTampered() {
        String written = "{\"kind\":\"data_board\",\"signals\":{\"magnitude\":0.9170061196527047}}";
        String readBack = "{\"kind\":\"data_board\",\"signals\":{\"magnitude\":0.9170061196527048}}";
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);
        when(mapper.selectOne(any())).thenReturn(row(readBack, FrozenContext.SCHEMA));

        // 前提：行里的摘要是写入时算的，与读回后的内容对不上——旧口径就是死在这里
        assertNotEquals(FrozenContext.hash(written), FrozenContext.hash(readBack));

        FrozenContext context = service(mapper).verify(30L, readBack);

        assertNotNull(context, "只是小数表示不同，内容并没有被改过");
    }

    @Test
    @DisplayName("真的被改过：照旧拒绝")
    void changedDecimalIsStillRejected() {
        String readBack = "{\"kind\":\"data_board\",\"signals\":{\"magnitude\":0.9170061196527048}}";
        HeronProductionContextMapper mapper = mock(HeronProductionContextMapper.class);
        when(mapper.selectOne(any())).thenReturn(row(readBack, FrozenContext.SCHEMA));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service(mapper).verify(30L, "{\"kind\":\"data_board\",\"signals\":{\"magnitude\":0.5}}"));

        assertEquals("news_context_mismatch", error.getCode());
    }
}
