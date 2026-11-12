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
}
