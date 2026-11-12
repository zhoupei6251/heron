package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片素材只来自原文。抽不出图就退化成纯文字，不编造、不借图。
 */
@DisplayName("正文抽图：只认原文自带的图")
class ArticleImagesTest {

    private static final String ARTICLE = "https://news.example.com/2026/11/post.html";

    @Test
    @DisplayName("抽出绝对地址与相对地址，并解析成绝对链接")
    void extractsAbsoluteAndRelative() {
        ArrayNode assets = ArticleImages.extract("""
            <p>正文</p>
            <img src="https://cdn.example.com/a.jpg" alt="图一">
            <p>中间</p>
            <img src="/upload/b.png">
            """, ARTICLE);
        assertEquals(2, assets.size());
        assertEquals("img_0", assets.get(0).path("asset_id").asString());
        assertEquals("https://cdn.example.com/a.jpg", assets.get(0).path("source_url").asString());
        assertEquals("https://news.example.com/upload/b.png", assets.get(1).path("source_url").asString());
        assertEquals(ARTICLE, assets.get(0).path("article_url").asString());
    }

    @Test
    @DisplayName("logo、图标、svg、追踪像素不算画面素材")
    void skipsNonContentImages() {
        ArrayNode assets = ArticleImages.extract("""
            <img src="https://x.com/logo.png">
            <img src="https://x.com/icon-arrow.png">
            <img src="https://x.com/blank.svg">
            <img src="https://x.com/pixel.gif">
            <img src="https://x.com/real-photo.jpg">
            """, ARTICLE);
        assertEquals(1, assets.size());
        assertEquals("https://x.com/real-photo.jpg", assets.get(0).path("source_url").asString());
    }

    @Test
    @DisplayName("同一张图出现两次只留一条")
    void deduplicates() {
        ArrayNode assets = ArticleImages.extract("""
            <img src="https://x.com/same.jpg">
            <img src="https://x.com/same.jpg">
            """, ARTICLE);
        assertEquals(1, assets.size());
    }

    @Test
    @DisplayName("data-src 懒加载图也能取到")
    void readsLazyLoadAttribute() {
        ArrayNode assets = ArticleImages.extract(
            "<img data-src=\"https://x.com/lazy.jpg\" src=\"data:image/gif;base64,R0lGOD\">", ARTICLE);
        assertEquals(1, assets.size());
        assertEquals("https://x.com/lazy.jpg", assets.get(0).path("source_url").asString());
    }

    @Test
    @DisplayName("数量有上限，不会把整页图塞进稿件")
    void capsAtMax() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            html.append("<img src=\"https://x.com/p").append(i).append(".jpg\">");
        }
        assertTrue(ArticleImages.extract(html.toString(), ARTICLE).size() <= 8);
    }

    @Test
    @DisplayName("没有图时提示词明确说没有，避免模型自造素材")
    void promptHintStatesAbsence() {
        String hint = ArticleImages.promptHint(ArticleImages.extract("<p>无图</p>", ARTICLE));
        assertTrue(hint.contains("没有可用图片"), hint);
    }

    @Test
    @DisplayName("有图时提示词列出 asset_id 与来源")
    void promptHintListsAssets() {
        ArrayNode assets = ArticleImages.extract("<img src=\"https://x.com/p.jpg\">", ARTICLE);
        String hint = ArticleImages.promptHint(assets);
        assertTrue(hint.contains("img_0"), hint);
        assertTrue(hint.contains("https://x.com/p.jpg"), hint);
        assertTrue(hint.contains("不要自造"), hint);
    }
}
