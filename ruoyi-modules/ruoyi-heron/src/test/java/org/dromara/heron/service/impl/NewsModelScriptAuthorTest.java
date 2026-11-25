package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写稿必须真的消费编辑决策：意图、理由、视角进提示词。
 * 之前这三个字段只躺在快照里，模型看到的是「标题 + 草稿 + 正文」——
 * 总编辑怎么想，对稿件没有影响，等于决策白做。
 */
@DisplayName("写稿提示词：消费编辑意图")
class NewsModelScriptAuthorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final NewsArticleBody ARTICLE = new NewsArticleBody(
        "https://example.com/a", LocalDateTime.of(2026, 11, 12, 8, 0), "<html>正文</html>", "a".repeat(64));
    private static final String STORYBOARD_SCRIPT = """
        {"title":"金价再创新高","closing":"先看后续证据。","source_name":"示例站","scenes":[
          {"scene_id":"scene_001","lead":"金价站上关键位","body":"最新收于四千美元。","duration_s":10,
           "scene_type":"headline","beat":"HOOK","on_screen_text":"关键位已站上"},
          {"scene_id":"scene_002","lead":"涨幅集中在下半年","body":"下半年累计抬升一百美元。","duration_s":10,
           "scene_type":"text","beat":"EVIDENCE","on_screen_text":"下半年抬升"},
          {"scene_id":"scene_003","lead":"拐点看下一期斜率","body":"本期数据暂不支持反转。","duration_s":10,
           "scene_type":"text","beat":"CONCLUSION","on_screen_text":"反转仍待证据"}]}
        """;

    private static JsonNode input(String json) {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("编辑意图、理由、视角都进提示词")
    void promptCarriesEditorialDecision() {
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"金价创新高","draft":"草稿",
             "editorial":{"editorialIntent":"讲清谁在买","reason":"数据对比强","storyAngle":"央行视角"}}
            """), ARTICLE, "");

        assertTrue(prompt.contains("编辑意图：讲清谁在买"), prompt);
        assertTrue(prompt.contains("为什么选它：数据对比强"), prompt);
        assertTrue(prompt.contains("故事视角：央行视角"), prompt);
    }

    @Test
    @DisplayName("老快照没有 editorial：整行省略，不塞空字符串")
    void legacyInputOmitsEditorialLines() {
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"金价创新高","draft":"草稿"}
            """), ARTICLE, "");

        assertFalse(prompt.contains("编辑意图"), prompt);
        assertFalse(prompt.contains("故事视角"), prompt);
        assertTrue(prompt.contains("正文 HTML"), "正文仍要进提示词");
    }

    @Test
    @DisplayName("只有意图没有角度时不编造角度")
    void partialEditorialIsHonest() {
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"标题","draft":"草稿","editorial":{"editorialIntent":"讲趋势"}}
            """), ARTICLE, "");

        assertTrue(prompt.contains("编辑意图：讲趋势"), prompt);
        assertFalse(prompt.contains("故事视角"), prompt);
    }

    @Test
    @DisplayName("模板场景能力进提示词：模型不该写渲染不出来的类型")
    void promptCarriesTemplateSceneCapability() {
        String capability = "本次模板（news-poster-9x16）只支持这些 scene_type，只能用这些：headline、text。";
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"标题","draft":"草稿"}
            """), ARTICLE, capability);

        assertTrue(prompt.contains("news-poster-9x16"), prompt);
        assertTrue(prompt.contains("headline、text"), prompt);
    }

    @Test
    @DisplayName("storyboard 配置开启时，作者提示词要求完整分镜三件套")
    void storyboardConfigAddsTripletRules(@TempDir Path dir) throws Exception {
        Path template = Files.createDirectory(dir.resolve("news-minimal-9x16"));
        Files.writeString(template.resolve("template.json"),
            "{\"supportedSceneTypes\":[\"headline\",\"text\"]}");
        Files.writeString(template.resolve("template.config.json"), "{\"storyboard\":true}");
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsTemplate(dir);
        NewsModelScriptAuthor author = new NewsModelScriptAuthor(null, null, properties);

        String capability = author.sceneCapability(input("""
            {"assets":{"templateKey":"news-minimal-9x16"}}
            """)).prompt();

        assertTrue(capability.contains("scene_type、beat、on_screen_text"), capability);
        assertTrue(capability.contains("首段 beat=HOOK"), capability);
        assertTrue(capability.contains("不超过 18 字"), capability);
        assertTrue(capability.contains("body 保留完整口播"), capability);
    }

    @Test
    @DisplayName("未开分镜的文稿模板只提示场景能力，不强迫旧稿写 beat")
    void nonStoryboardConfigKeepsLegacyPrompt(@TempDir Path dir) throws Exception {
        Path template = Files.createDirectory(dir.resolve("news-poster-9x16"));
        Files.writeString(template.resolve("template.json"),
            "{\"supportedSceneTypes\":[\"headline\",\"text\"]}");
        Files.writeString(template.resolve("template.config.json"), "{\"storyboard\":false}");
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsTemplate(dir);
        NewsModelScriptAuthor author = new NewsModelScriptAuthor(null, null, properties);

        String capability = author.sceneCapability(input("""
            {"assets":{"templateKey":"news-poster-9x16"}}
            """)).prompt();

        assertTrue(capability.contains("headline、text"), capability);
        assertFalse(capability.contains("已启用分镜"), capability);
    }

    @Test
    @DisplayName("分镜模板的新稿不能整篇省略 beat 与上屏文字")
    void storyboardTemplateRejectsLegacyDraft() {
        String legacy = STORYBOARD_SCRIPT
            .replaceAll(",\"beat\":\"[A-Z]+\"", "")
            .replaceAll(",\"on_screen_text\":\"[^\"]+\"", "");
        java.util.List<String> violations = NewsModelScriptAuthor.violations(JSON.readTree(legacy), true);

        assertTrue(violations.stream().anyMatch(item -> item.contains("缺少 beat")), violations.toString());
        assertTrue(violations.stream().anyMatch(item -> item.contains("缺少 on_screen_text")), violations.toString());
        assertTrue(NewsModelScriptAuthor.violations(JSON.readTree(legacy), false).stream()
            .noneMatch(item -> item.contains("缺少 beat")), "旧模板仍要兼容没有分镜的稿件");
    }

    @Test
    @DisplayName("完整 article 分镜通过已启用模板的作者链路并保留标题与正文")
    void completeStoryboardPassesAuthor(@TempDir Path dir) throws Exception {
        writeTemplate(dir, "news-minimal-9x16", "{\"storyboard\":true}");
        StoryboardClient client = new StoryboardClient(STORYBOARD_SCRIPT);
        JsonNode script = JSON.readTree(author(client, dir).author(run("news-minimal-9x16")));

        assertEquals(1, client.calls);
        assertTrue(client.lastPrompt.contains("已启用分镜"), client.lastPrompt);
        assertTrue(client.lastPrompt.contains("scene_type、beat、on_screen_text"), client.lastPrompt);
        assertEquals("金价再创新高", script.path("title").asString());
        JsonNode first = script.path("scenes").get(0);
        assertEquals("最新收于四千美元。", first.path("body").asString());
        assertEquals("HOOK", first.path("beat").asString());
        assertEquals("关键位已站上", first.path("on_screen_text").asString());
    }

    @Test
    @DisplayName("半拉子分镜进入一次定向修复，仍不完整就退回写稿")
    void partialStoryboardRepairsOnceThenFails(@TempDir Path dir) throws Exception {
        writeTemplate(dir, "news-minimal-9x16", "{\"storyboard\":true}");
        StoryboardClient client = new StoryboardClient(
            STORYBOARD_SCRIPT.replace(",\"beat\":\"EVIDENCE\"", ""));
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> author(client, dir).author(run("news-minimal-9x16")));

        assertEquals("news_script_contract", error.getCode());
        assertTrue(error.getMessage().contains("分镜不完整"), error.getMessage());
        assertEquals(2, client.calls);
        assertTrue(client.lastPrompt.contains("分镜不完整"), client.lastPrompt);
        assertTrue(client.lastPrompt.contains("已启用分镜"), client.lastPrompt);
    }

    @Test
    @DisplayName("审稿修复也消费已启用模板的分镜约束")
    void reviewRepairUsesEnabledStoryboardTemplate(@TempDir Path dir) throws Exception {
        writeTemplate(dir, "news-minimal-9x16", "{\"storyboard\":true}");
        StoryboardClient client = new StoryboardClient(STORYBOARD_SCRIPT);

        JsonNode repaired = JSON.readTree(author(client, dir).repair(
            run("news-minimal-9x16"), STORYBOARD_SCRIPT, java.util.List.of("补充证据边界")));

        assertEquals(1, client.calls);
        assertTrue(client.lastPrompt.contains("已启用分镜"), client.lastPrompt);
        assertTrue(client.lastPrompt.contains("补充证据边界"), client.lastPrompt);
        assertEquals("金价再创新高", repaired.path("title").asString());
        assertEquals("最新收于四千美元。", repaired.path("scenes").get(0).path("body").asString());
        assertEquals("HOOK", repaired.path("scenes").get(0).path("beat").asString());
        assertEquals("关键位已站上", repaired.path("scenes").get(0).path("on_screen_text").asString());
    }

    @Test
    @DisplayName("旧无 beat 稿在已启用分镜模板下审稿修复仍保持旧语义")
    void legacyRepairKeepsOldSemanticsUnderStoryboardTemplate(@TempDir Path dir) throws Exception {
        writeTemplate(dir, "news-minimal-9x16", "{\"storyboard\":true}");
        String legacy = STORYBOARD_SCRIPT.replaceAll(",\"scene_type\":\"[^\"]+\"", "")
            .replaceAll(",\"beat\":\"[A-Z]+\"", "")
            .replaceAll(",\"on_screen_text\":\"[^\"]+\"", "");
        StoryboardClient client = new StoryboardClient(legacy);

        JsonNode repaired = JSON.readTree(author(client, dir).repair(
            run("news-minimal-9x16"), legacy, List.of("澄清证据边界")));

        assertEquals(1, client.calls);
        assertTrue(repaired.path("scenes").get(0).path("beat").isMissingNode());
        assertTrue(repaired.path("scenes").get(0).path("on_screen_text").isMissingNode());
        assertTrue(NewsModelScriptAuthor.violations(repaired, false).isEmpty());
    }

    @Test
    @DisplayName("非法分镜 JSON 返回明确业务错误，不降级成旧稿")
    void invalidStoryboardConfigFailsClosed(@TempDir Path dir) throws Exception {
        writeTemplate(dir, "news-minimal-9x16", "{");
        NewsModelScriptAuthor author = author(new StoryboardClient(STORYBOARD_SCRIPT), dir);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> author.sceneCapability(input("""
                {"assets":{"templateKey":"news-minimal-9x16"}}
                """)));

        assertEquals("news_template_config", error.getCode());
        assertTrue(error.getMessage().contains("news-minimal-9x16"), error.getMessage());
    }

    @Test
    @DisplayName("缺失分镜配置保持旧稿兼容，但仍注入模板场景能力")
    void missingStoryboardConfigKeepsLegacySemantics(@TempDir Path dir) throws Exception {
        Path template = Files.createDirectory(dir.resolve("news-minimal-9x16"));
        Files.writeString(template.resolve("template.json"),
            "{\"supportedSceneTypes\":[\"headline\",\"text\"]}");
        NewsModelScriptAuthor author = author(new StoryboardClient(STORYBOARD_SCRIPT), dir);

        NewsModelScriptAuthor.SceneCapability capability = author.sceneCapability(input("""
            {"assets":{"templateKey":"news-minimal-9x16"}}
            """));

        assertFalse(capability.storyboard());
        assertTrue(capability.prompt().contains("headline、text"), capability.prompt());
    }

    private static void writeTemplate(Path dir, String key, String config) throws Exception {
        Path template = Files.createDirectory(dir.resolve(key));
        Files.writeString(template.resolve("template.json"),
            "{\"supportedSceneTypes\":[\"headline\",\"text\"]}");
        Files.writeString(template.resolve("template.config.json"), config);
    }

    private static NewsModelScriptAuthor author(OpenAiModelClient client) {
        return author(client, null);
    }

    private static NewsModelScriptAuthor author(OpenAiModelClient client, Path templateRoot) {
        FrozenArticleService articles = org.mockito.Mockito.mock(FrozenArticleService.class);
        org.mockito.Mockito.when(articles.of(org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.any(JsonNode.class))).thenReturn(ARTICLE);
        HeronProductionProperties properties = new HeronProductionProperties();
        if (templateRoot != null) {
            properties.setNewsTemplate(templateRoot);
        }
        return new NewsModelScriptAuthor(client, articles, properties);
    }

    private static ProductionRunVo run(String templateKey) {
        return new ProductionRunVo(1L, ProductionBizType.news_explainer, 7L, 9L, 1, "token",
            "{\"title\":\"金价再创新高\",\"draft\":\"草稿\",\"assets\":{\"templateKey\":\""
                + templateKey + "\"}}");
    }

    private static final class StoryboardClient extends OpenAiModelClient {
        private final String reply;
        private int calls;
        private String lastPrompt;

        private StoryboardClient(String reply) {
            super(null, null, null);
            this.reply = reply;
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            calls++;
            lastPrompt = userPrompt;
            return reply;
        }
    }
}
