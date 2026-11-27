package org.dromara.heron.agentscope;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹药库工具契约：索引现算 frontmatter（卡片增删即刻生效）、读卡越界拒绝、
 * 参照资产与渲染产物互不越权（工具面只读弹药库，只写镜头 HTML）。
 */
@DisplayName("镜头创作：弹药库参考卡")
class ShotRenderToolsReferenceCardTest {

    @TempDir
    Path work;

    private ShotRenderTools tools() {
        Path root = work.resolve("creative-knowledge");
        return new ShotRenderTools(work.resolve("shots"), null, Map.of("profileId", "test"),
            ":root{}", "narrative", "scene-1", null, root);
    }

    @Test
    @DisplayName("索引按 frontmatter 现算，无 frontmatter 的说明文件跳过")
    void indexComesFromFrontmatter() {
        Path cards = work.resolve("creative-knowledge/reference-cards");
        write(cards.resolve("camera/crash-zoom-punch.md"), """
            ---
            name: crash-zoom-punch
            一句话: 全景一拍急推到目标特写
            适用: 功能段点名镜头
            时长: 约 0.5s
            能量: 高
            标签: effects
            ---
            正文
            """);
        write(cards.resolve("camera/plain-note.md"), "纯文本说明，不是参照卡");
        write(cards.resolve("camera/ATTRIBUTION.md"), "# 来源与授权说明");
        Path root = work.resolve("creative-knowledge");
        write(root.resolve("LICENSE"), "MIT");
        ShotRenderTools tools = tools();
        Map<String, Object> index = tools.listReferenceCards();
        assertEquals(1, index.get("count"));
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, String>>> groups = (Map<String, List<Map<String, String>>>) index.get("cards");
        Map<String, String> card = groups.get("camera").getFirst();
        assertEquals("camera/crash-zoom-punch", card.get("id"));
        assertEquals("全景一拍急推到目标特写", card.get("一句话"));
        assertEquals("高", card.get("能量"));
    }

    @Test
    @DisplayName("读卡正文完整返回，非法 id 与越界路径拒绝")
    void readCardValidatesBoundaries() {
        Path cards = work.resolve("creative-knowledge/reference-cards");
        write(cards.resolve("camera/card.md"), "---\n一句话: x\n---\n## 意图\n把产品拍成旅行");
        ShotRenderTools tools = tools();
        String text = tools.readReferenceCard("camera/card");
        assertTrue(text.contains("## 意图"));
        assertThrows(HeronBusinessException.class, () -> tools.readReferenceCard("camera/missing"));
        assertThrows(HeronBusinessException.class, () -> tools.readReferenceCard("../escape"));
        assertThrows(HeronBusinessException.class, () -> tools.readReferenceCard("flat"));
    }

    @Test
    @DisplayName("弹药库缺失时索引返回零并提示，不阻断创作")
    void missingLibraryDegradesGracefully() {
        ShotRenderTools tools = tools();
        Map<String, Object> index = tools.listReferenceCards();
        assertEquals(0, index.get("count"));
        assertFalse(((String) index.get("note")).isBlank());
        assertThrows(HeronBusinessException.class, () -> tools.readReferenceCard("camera/any"));
    }

    @Test
    @DisplayName("真实弹药库索引可扫出三类参照且规模合理")
    void realLibraryIndexes() {
        Path repo = Path.of("../..").toAbsolutePath().normalize();
        if (!Files.isDirectory(repo.resolve("data/creative-knowledge/reference-cards"))) {
            return;
        }
        ShotRenderTools tools = new ShotRenderTools(work, null, Map.of("profileId", "test"),
            ":root{}", "narrative", "scene-1", null, repo.resolve("data/creative-knowledge"));
        Map<String, Object> index = tools.listReferenceCards();
        int count = (int) index.get("count");
        assertEquals(190, count, "124 镜头配方 + 57 画派风格 + 9 解说语法");
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, String>>> groups = (Map<String, List<Map<String, String>>>) index.get("cards");
        assertTrue(groups.containsKey("video-shotcraft/camera"));
        String vox = tools.readReferenceCard("huashu-art-motion/animation-grammar/y2_vox");
        assertNotNull(vox);
        assertTrue(vox.contains("Vox"));
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
