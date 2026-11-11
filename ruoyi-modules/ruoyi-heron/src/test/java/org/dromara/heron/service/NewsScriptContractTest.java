package org.dromara.heron.service;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.NewsArticleBody;
import org.dromara.heron.service.impl.NewsModelScriptAuthor;
import org.dromara.heron.service.impl.OpenAiModelClient;
import org.dromara.heron.service.impl.NewsScriptContract;
import org.dromara.heron.service.impl.NewsSourceFetcher;
import org.dromara.heron.service.ModelClientTest.ConfigStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 稿件契约闸：只判形状与出处事实，不判版式、不自动改稿。 */
@DisplayName("N2 稿件 JSON 契约闸与写稿作者")
class NewsScriptContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHA = "a".repeat(64);
    private static final String BODY_SHA = DigestUtil.sha256Hex("<html>正文</html>".getBytes(StandardCharsets.UTF_8));

    static JsonNode script() {
        return JSON.readTree("{\"source_url\":\"https://feed.example.test/news/1\",\"fetched_at\":\"2026-11-02T10:00:00Z\","
            + "\"body_sha256\":\"" + SHA + "\",\"title\":\"测试新闻\",\"closing\":\"结尾一句话\",\"source_name\":\"测试来源\","
            + "\"scenes\":[{\"lead\":\"领句一\",\"body\":\"正文一\",\"duration_s\":15},"
            + "{\"lead\":\"领句二\",\"body\":\"正文二\",\"duration_s\":15},"
            + "{\"lead\":\"领句三\",\"body\":\"正文三\",\"duration_s\":15}]}");
    }

    @Test
    @DisplayName("合规稿件没有违例条目")
    void compliantScriptPasses() {
        assertEquals(List.of(), NewsScriptContract.violations(script()));
    }

    @Test
    @DisplayName("逐条列出缺失出处、时长合计、段落数、重复与未知字段")
    void listsEveryViolationWithoutFixingThem() {
        List<String> missingProvenance = NewsScriptContract.violations(JSON.readTree(
            "{\"title\":\"测试新闻\",\"closing\":\"结尾\",\"source_name\":\"来源\","
                + "\"scenes\":[{\"lead\":\"一\",\"body\":\"正文\",\"duration_s\":15},{\"lead\":\"二\",\"body\":\"正文\",\"duration_s\":15},"
                + "{\"lead\":\"三\",\"body\":\"正文\",\"duration_s\":15}]}"));
        assertTrue(missingProvenance.contains("缺字段 source_url"), missingProvenance.toString());
        assertTrue(missingProvenance.contains("缺字段 body_sha256"), missingProvenance.toString());

        List<String> tooShort = NewsScriptContract.violations(
            JSON.readTree(script().toString().replace("\"duration_s\":15", "\"duration_s\":5")));
        assertTrue(tooShort.stream().anyMatch(item -> item.contains("总时长")), tooShort.toString());

        List<String> tooManyScenes = NewsScriptContract.violations(JSON.readTree(script().toString().replace(
            "{\"lead\":\"领句三\",\"body\":\"正文三\",\"duration_s\":15}",
            "{\"lead\":\"领句三\",\"body\":\"正文三\",\"duration_s\":15},{\"lead\":\"四\",\"body\":\"正文\",\"duration_s\":15},"
                + "{\"lead\":\"五\",\"body\":\"正文\",\"duration_s\":15},{\"lead\":\"六\",\"body\":\"正文\",\"duration_s\":15},"
                + "{\"lead\":\"七\",\"body\":\"正文\",\"duration_s\":15}")));
        assertTrue(tooManyScenes.stream().anyMatch(item -> item.contains("段落数")), tooManyScenes.toString());

        List<String> duplicatedTitle = NewsScriptContract.violations(
            JSON.readTree(script().toString().replace("\"lead\":\"领句一\"", "\"lead\":\"测试新闻\"")));
        assertTrue(duplicatedTitle.stream().anyMatch(item -> item.contains("重复")), duplicatedTitle.toString());

        assertEquals(List.of("未知字段 html"), NewsScriptContract.violations(
            JSON.readTree(script().toString().replace("\"closing\"", "\"html\":\"<p>x</p>\",\"closing\""))));
    }

    @Test
    @DisplayName("作者分送契约与需求，出处事实取抓取而非模型自述")
    void authorInjectsFetchedProvenance() {
        ScriptClient client = new ScriptClient("{\"title\":\"测试新闻\",\"closing\":\"结尾\",\"source_name\":\"测试来源\","
            + "\"scenes\":[{\"lead\":\"一\",\"body\":\"正文\",\"duration_s\":15},{\"lead\":\"二\",\"body\":\"正文\",\"duration_s\":15},"
            + "{\"lead\":\"三\",\"body\":\"正文\",\"duration_s\":15}]}");
        JsonNode node = JSON.readTree(new NewsModelScriptAuthor(client, new StubFetcher()).author(run()));

        assertEquals("script", client.stage);
        assertTrue(client.systemPrompt.contains("duration_s"), client.systemPrompt);
        assertTrue(client.userPrompt.contains("测试新闻标题"), client.userPrompt);
        assertTrue(client.userPrompt.contains("来源事实"), client.userPrompt);
        assertTrue(client.userPrompt.contains("<html>正文</html>"), client.userPrompt);
        assertEquals("https://feed.example.test/news/1", node.path("source_url").asString());
        assertEquals(BODY_SHA, node.path("body_sha256").asString());
        assertFalse(node.path("fetched_at").asString().isBlank());
    }

    @Test
    @DisplayName("契约不过先定向修复一次，仍不过就退回写稿并列条目")
    void authorRepairsOnceThenFailsWithItemList() {
        ScriptClient client = new ScriptClient("{\"title\":\"很短\",\"closing\":\"结尾\",\"source_name\":\"来源\","
            + "\"scenes\":[{\"lead\":\"一\",\"body\":\"正文\",\"duration_s\":15}]}");
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> new NewsModelScriptAuthor(client, new StubFetcher()).author(run()));
        assertEquals("news_script_contract", error.getCode());
        assertTrue(error.getMessage().contains("段落数"), error.getMessage());
        assertEquals(2, client.calls);
        assertEquals("script-repair", client.stage);
    }

    @Test
    @DisplayName("候选证据里没有正文出处就拒绝写稿")
    void authorRefusesCandidateWithoutSourceUrl() {
        String input = JSON.writeValueAsString(Map.of("workId", 7L, "title", "测试新闻标题", "draft", "手工摘要",
            "evidence", Map.of("items", List.of(Map.of("manual", true, "title", "测试新闻标题")))));
        ScriptClient client = new ScriptClient("{}");
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> new NewsModelScriptAuthor(client, new StubFetcher()).author(
                new ProductionRunVo(1L, ProductionBizType.news_video, 7L, 9L, 1, "token", input, null)));
        assertEquals("news_script_source", error.getCode());
        assertEquals(0, client.calls);
    }

    private ProductionRunVo run() {
        String input = JSON.writeValueAsString(Map.of("workId", 7L, "candidateId", 8L, "versionNo", 1, "revision", 1L,
            "title", "测试新闻标题", "draft", "来源事实与授权待人工核对",
            "evidence", Map.of("items", List.of(Map.of("sourceUrl", "https://feed.example.test/news/1", "title", "测试新闻标题"))),
            "assets", Map.of("templateKey", "default", "voiceKey", "default")));
        return new ProductionRunVo(1L, ProductionBizType.news_video, 7L, 9L, 1, "token", input, null);
    }

    /** 只记录提示词，不参与 HTTP。 */
    static class ScriptClient extends OpenAiModelClient {
        String stage;
        String systemPrompt;
        String userPrompt;
        String text;
        int calls;

        ScriptClient(String text) {
            super(new ConfigStub(Map.of()), new StandardEnvironment(), null);
            this.text = text;
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            this.stage = stage;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            calls++;
            return text;
        }
    }

    /** 假抓取：证明作者用的是抓取所得出处事实，同时不发起任何出网。 */
    static class StubFetcher extends NewsSourceFetcher {
        StubFetcher() {
            super(null, null, null);
        }

        @Override
        public NewsArticleBody fetchArticle(URI url) {
            String html = "<html>正文</html>";
            return new NewsArticleBody(url.toString(), LocalDateTime.of(2026, 11, 2, 10, 0), html,
                DigestUtil.sha256Hex(html.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
