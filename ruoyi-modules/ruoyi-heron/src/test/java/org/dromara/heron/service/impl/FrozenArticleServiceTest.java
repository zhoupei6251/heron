package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionArtifact;
import org.dromara.heron.mapper.HeronProductionArtifactMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 正文冻结层的两条硬规则：自动路径不许退回「第一条」，已冻结的正文不许被悄悄换掉。
 */
@DisplayName("正文冻结：出处严格匹配与摘要校验")
class FrozenArticleServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode input(String json) {
        return JSON.readTree(json);
    }

    private static FrozenArticleService service(HeronProductionArtifactMapper mapper) {
        return new FrozenArticleService(mapper, null);
    }

    @Test
    @DisplayName("自动路径：命中选中的证据才取正文")
    void autoPathUsesSelectedEvidence() {
        JsonNode input = input("""
            {"selection":{"sourceEvidenceIds":["b"]},
             "evidence":{"items":[
               {"sourceItemId":"a","sourceUrl":"https://example.com/a"},
               {"sourceItemId":"b","sourceUrl":"https://example.com/b"}]}}
            """);

        assertEquals("https://example.com/b", FrozenArticleService.articleUrl(input).toString());
    }

    @Test
    @DisplayName("自动路径：选中证据定位不到就失败，不退回第一条")
    void autoPathNeverFallsBack() {
        JsonNode input = input("""
            {"selection":{"sourceEvidenceIds":["missing"]},
             "evidence":{"items":[
               {"sourceItemId":"a","sourceUrl":"https://example.com/a"}]}}
            """);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> FrozenArticleService.articleUrl(input));

        assertEquals("news_script_source", error.getCode());
    }

    @Test
    @DisplayName("手工路径：没有 selection 时按 legacy 取第一条带链接的证据")
    void legacyPathKeepsFallback() {
        JsonNode input = input("""
            {"evidence":{"items":[
               {"sourceItemId":"a","sourceUrl":"https://example.com/a"},
               {"sourceItemId":"b","sourceUrl":"https://example.com/b"}]}}
            """);

        assertEquals("https://example.com/a", FrozenArticleService.articleUrl(input).toString());
    }

    @Test
    @DisplayName("没有正文出处：失败，不写一条没出处的稿")
    void missingSourceFails() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> FrozenArticleService.articleUrl(input("{\"evidence\":{\"items\":[]}}")));

        assertEquals("news_script_source", error.getCode());
    }

    @Test
    @DisplayName("已冻结的正文被改过：拒绝使用")
    void tamperedArtifactIsRejected() {
        HeronProductionArtifactMapper mapper = mock(HeronProductionArtifactMapper.class);
        HeronProductionArtifact row = new HeronProductionArtifact();
        row.setTaskId(30L);
        row.setKind(FrozenArticleService.KIND_ARTICLE);
        row.setContent("{\"html\":\"body\"}");
        row.setContentSha256("0".repeat(64));
        when(mapper.selectOne(any())).thenReturn(row);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service(mapper).article(30L));

        assertEquals("news_artifact_mismatch", error.getCode());
    }

    @Test
    @DisplayName("没有冻结正文时返回 null，由调用方抓取后再存")
    void missingArtifactReturnsNull() {
        HeronProductionArtifactMapper mapper = mock(HeronProductionArtifactMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        FrozenArticleService service = service(mapper);

        assertNull(service.article(30L));
        verify(mapper, times(1)).selectOne(any());
        verify(mapper, never()).insert(any(HeronProductionArtifact.class));
    }

    @Test
    @DisplayName("存进去的内容带抓取时间，读回来逐项一致")
    void roundTripsBody() {
        HeronProductionArtifactMapper mapper = mock(HeronProductionArtifactMapper.class);
        when(mapper.insert(any(HeronProductionArtifact.class))).thenReturn(1);
        NewsArticleBody body = new NewsArticleBody("https://example.com/a",
            LocalDateTime.of(2026, 11, 12, 8, 0), "<html>正文</html>", "a".repeat(64));

        NewsArticleBody saved = service(mapper).freeze(30L, body);
        assertEquals(body, saved);
        verify(mapper).insert(any(HeronProductionArtifact.class));
    }

    @Test
    @DisplayName("任务号为 0 的调用不查库")
    void nonTaskCallerSkipsLookup() {
        HeronProductionArtifactMapper mapper = mock(HeronProductionArtifactMapper.class);

        assertNull(service(mapper).article(0L));
        verify(mapper, never()).selectOne(any());
    }
}
