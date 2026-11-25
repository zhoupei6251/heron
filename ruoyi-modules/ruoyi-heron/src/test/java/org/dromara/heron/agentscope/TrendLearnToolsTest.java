package org.dromara.heron.agentscope;

import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendLearnToolsTest {

    @TempDir
    Path tempDir;

    private Path templates;
    private Path breakdown;
    private Path storyboard;
    private TrendLearnTools tools;

    @BeforeEach
    void setUp() throws Exception {
        templates = tempDir.resolve("templates");
        Path shared = templates.resolve("_shared");
        Files.createDirectories(shared.resolve("tokens"));
        Files.writeString(shared.resolve("styles.json"), """
            {"data-board":{"label":"动态数据解释","role":"排行与趋势"}}
            """);
        Files.writeString(shared.resolve("tokens/data-board.css"), ":root { --accent: #18b7c9; }");
        Path layout = templates.resolve("layouts/data-board");
        Files.createDirectories(layout);
        Files.writeString(layout.resolve("news-data-9x16.css"), "#title { font-size: 92px; }");
        Path template = templates.resolve("news-data-9x16");
        Files.createDirectories(template);
        Files.writeString(template.resolve("template.config.json"), """
            {"key":"news-data-9x16","name":"数据看板","style":"data-board","line":"DATA_BOARD",
             "layout":"news-data-9x16","capabilities":["chart"],"supportedSceneTypes":["chart"],"storyboard":true}
            """);
        Files.writeString(template.resolve("template.json"), "{\"name\":\"数据看板\"}");
        breakdown = tempDir.resolve("breakdown.json");
        Files.writeString(breakdown, "{\"shots\":[{\"duration\":2.4}]}");
        storyboard = tempDir.resolve("storyboard.jpg");
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.fillRect(0, 0, 320, 180);
        graphics.dispose();
        ImageIO.write(image, "jpg", storyboard.toFile());

        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsTemplate(templates);
        ResearchBudget budget = new ResearchBudget(10, 10_000, Duration.ofMinutes(1), Clock.systemUTC(), null);
        tools = new TrendLearnTools(properties, breakdown, storyboard, budget);
    }

    @Test
    void listsTemplateCapabilitiesAndReadsTokensAndLayout() {
        Map<String, Object> list = tools.listTemplates();
        assertEquals(1, list.get("count"));
        assertTrue(tools.hasTemplate("news-data-9x16"));
        assertFalse(tools.hasTemplate("missing"));
        String template = tools.readTemplate("news-data-9x16");

        assertTrue(template.contains("--accent"));
        assertTrue(template.contains("#title"));
        assertTrue(template.contains("动态数据解释"));
    }

    @Test
    void rejectsTemplatePathTraversal() {
        assertThrows(HeronBusinessException.class, () -> tools.readTemplate("../outside"));
    }

    @Test
    void viewFrameReturnsActualImageBlock() {
        ToolResultBlock result = tools.viewFrame().block();

        assertTrue(result.getOutput().stream().anyMatch(DataBlock.class::isInstance));
    }

    @Test
    void budgetRejectsExtraToolCalls() {
        ResearchBudget budget = new ResearchBudget(1, 10_000, Duration.ofMinutes(1), Clock.systemUTC(), null);
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsTemplate(templates);
        TrendLearnTools bounded = new TrendLearnTools(properties, breakdown, storyboard, budget);
        bounded.readBreakdown();

        HeronBusinessException error = assertThrows(HeronBusinessException.class, bounded::listTemplates);
        assertEquals(ResearchBudget.TOOL_EXCEEDED, error.getCode());
    }
}
