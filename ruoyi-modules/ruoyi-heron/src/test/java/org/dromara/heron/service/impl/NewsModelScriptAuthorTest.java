package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    private static JsonNode input(String json) {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("编辑意图、理由、视角都进提示词")
    void promptCarriesEditorialDecision() {
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"金价创新高","draft":"草稿",
             "editorial":{"editorialIntent":"讲清谁在买","reason":"数据对比强","storyAngle":"央行视角"}}
            """), ARTICLE);

        assertTrue(prompt.contains("编辑意图：讲清谁在买"), prompt);
        assertTrue(prompt.contains("为什么选它：数据对比强"), prompt);
        assertTrue(prompt.contains("故事视角：央行视角"), prompt);
    }

    @Test
    @DisplayName("老快照没有 editorial：整行省略，不塞空字符串")
    void legacyInputOmitsEditorialLines() {
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"金价创新高","draft":"草稿"}
            """), ARTICLE);

        assertFalse(prompt.contains("编辑意图"), prompt);
        assertFalse(prompt.contains("故事视角"), prompt);
        assertTrue(prompt.contains("正文 HTML"), "正文仍要进提示词");
    }

    @Test
    @DisplayName("只有意图没有角度时不编造角度")
    void partialEditorialIsHonest() {
        String prompt = NewsModelScriptAuthor.prompt(input("""
            {"title":"标题","draft":"草稿","editorial":{"editorialIntent":"讲趋势"}}
            """), ARTICLE);

        assertTrue(prompt.contains("编辑意图：讲趋势"), prompt);
        assertFalse(prompt.contains("故事视角"), prompt);
    }
}
