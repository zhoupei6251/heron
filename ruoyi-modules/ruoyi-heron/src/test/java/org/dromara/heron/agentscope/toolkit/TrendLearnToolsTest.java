package org.dromara.heron.agentscope.toolkit;

import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.dromara.heron.agentscope.research.ResearchBudget;
import org.dromara.heron.agentscope.research.ResearchVerification;
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

    private Path profiles;
    private Path breakdown;
    private Path storyboard;
    private TrendLearnTools tools;

    @BeforeEach
    void setUp() throws Exception {
        profiles = tempDir.resolve("profiles");
        Path knowledge = tempDir.resolve("creative-knowledge");
        Files.createDirectories(knowledge.resolve("styles"));
        Files.writeString(knowledge.resolve("styles/template-styles.json"), """
            {"data-board":{"label":"动态数据解释","role":"排行与趋势"}}
            """);
        Path profile = profiles.resolve("data-board");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("profile.json"), """
            {"profileId":"data-board","bizLine":"data_board","canvas":{"width":1080,"height":1920}}
            """);
        Files.writeString(profile.resolve("tokens.css"), ":root { --accent: #18b7c9; }");
        breakdown = tempDir.resolve("breakdown.json");
        Files.writeString(breakdown, "{\"shots\":[{\"duration\":2.4}]}");
        storyboard = tempDir.resolve("storyboard.jpg");
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.fillRect(0, 0, 320, 180);
        graphics.dispose();
        ImageIO.write(image, "jpg", storyboard.toFile());

        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setNewsProfile(profiles);
        ResearchBudget budget = new ResearchBudget(10, 10_000, Duration.ofMinutes(1), Clock.systemUTC(), null);
        tools = new TrendLearnTools(properties, breakdown, storyboard, budget);
    }

    @Test
    void listsProfileCapabilitiesAndReadsTokens() {
        Map<String, Object> list = tools.listProfiles();
        assertEquals(1, list.get("count"));
        assertTrue(tools.hasProfile("data-board"));
        assertFalse(tools.hasProfile("missing"));
        String profile = tools.readProfile("data-board");

        assertTrue(profile.contains("--accent"));
        assertTrue(profile.contains("1080"));
        assertTrue(profile.contains("动态数据解释"));
    }

    @Test
    void rejectsProfilePathTraversal() {
        assertThrows(HeronBusinessException.class, () -> tools.readProfile("../outside"));
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
        properties.setNewsProfile(profiles);
        TrendLearnTools bounded = new TrendLearnTools(properties, breakdown, storyboard, budget);
        bounded.readBreakdown();

        HeronBusinessException error = assertThrows(HeronBusinessException.class, bounded::listProfiles);
        assertEquals(ResearchBudget.TOOL_EXCEEDED, error.getCode());
    }
}
