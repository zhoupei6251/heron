package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("封面图：首屏运动选帧、比例与纯色闸")
class NewsCoverTest {

    @Test
    @DisplayName("在首屏中点正负六百毫秒内选相邻变化最大的一帧")
    void selectsMostChangedFrameInsideFirstScene(@TempDir Path root) throws Exception {
        Path template = template(root);
        Fixture fixture = new Fixture(root, false);

        NewsCover.Result result = NewsCover.create(fixture.processes, fixture.run, fixture.work,
            fixture.work.resolve("final.mp4"), template, new NewsNarrationTiming.Span("scene_001", 0, 3000, false));

        assertEquals(1500L, result.gate().measured().get("atMs"));
        assertEquals(0L, result.gate().measured().get("startMs"));
        assertEquals(3000L, result.gate().measured().get("endMs"));
        assertEquals(160, result.gate().measured().get("width"));
        assertEquals(284, result.gate().measured().get("height"));
        assertEquals(1080, result.gate().measured().get("thumbWidth"));
        assertEquals(1080, result.gate().measured().get("thumbHeight"));
        assertTrue(Boolean.TRUE.equals(result.gate().measured().get("nonBlank")));
        assertEquals(160, ImageIO.read(result.cover().toFile()).getWidth());
        assertEquals(284, ImageIO.read(result.cover().toFile()).getHeight());
        assertEquals(1080, ImageIO.read(result.thumb().toFile()).getWidth());
        assertEquals(1080, ImageIO.read(result.thumb().toFile()).getHeight());
        assertTrue(fixture.candidateTimes.stream().allMatch(time -> time >= 900 && time <= 2100));
    }

    @Test
    @DisplayName("方图是纯色时也不能通过交付闸")
    void rejectsSolidThumb(@TempDir Path root) throws Exception {
        Path template = template(root);
        Fixture fixture = new Fixture(root, false, true);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> NewsCover.create(fixture.processes, fixture.run, fixture.work, fixture.work.resolve("final.mp4"),
                template, new NewsNarrationTiming.Span("scene_001", 0, 3000, false)));

        assertEquals("news_cover_invalid", error.getCode());
    }

    @Test
    @DisplayName("纯色封面不能通过交付闸")
    void rejectsSolidCover(@TempDir Path root) throws Exception {
        Path template = template(root);
        Fixture fixture = new Fixture(root, true);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> NewsCover.create(fixture.processes, fixture.run, fixture.work, fixture.work.resolve("final.mp4"),
                template, new NewsNarrationTiming.Span("scene_001", 0, 3000, false)));

        assertEquals("news_cover_invalid", error.getCode());
        assertTrue(error.getMessage().contains("纯色"));
    }

    private static Path template(Path root) throws Exception {
        Path template = Files.createDirectories(root.resolve("templates/news"));
        Files.writeString(template.resolve("template.config.json"), """
            {"key":"news","width":160,"height":284}
            """);
        return template;
    }

    private static final class Fixture {
        private final ProductionRunVo run = new ProductionRunVo(9100L, ProductionBizType.news_explainer, 7L, 4L, 1,
            UUID.randomUUID().toString(), "{}", null);
        private final Path work;
        private final ProductionProcessService processes;
        private final List<Integer> candidateTimes = new java.util.ArrayList<>();

        private Fixture(Path root, boolean solidCover) throws Exception {
            this(root, solidCover, false);
        }

        private Fixture(Path root, boolean solidCover, boolean solidThumb) throws Exception {
            HeronProductionProperties properties = new HeronProductionProperties();
            properties.setDataRoot(root.resolve("data"));
            properties.setGuardEnabled(false);
            ProductionArtifactService artifacts = new ProductionArtifactService(properties, null);
            work = artifacts.prepareWorkDirectory(run);
            Files.writeString(work.resolve("final.mp4"), "video");
            processes = new ProductionProcessService(properties, artifacts) {
                @Override
                public ProductionProcessVo run(ProductionRunVo ignored, ProductionProcessBo command) {
                    List<String> args = command.arguments();
                    String output = Path.of(args.getLast()).getFileName().toString();
                    try {
                        if (output.startsWith("cover-candidate-")) {
                            int millis = (int) Math.round(Double.parseDouble(args.get(args.indexOf("-ss") + 1)) * 1000);
                            candidateTimes.add(millis);
                            int shade = switch (candidateTimes.size()) {
                                case 1 -> 20;
                                case 2 -> 30;
                                case 3 -> 130;
                                case 4 -> 140;
                                default -> 150;
                            };
                            writeImage(command.workDirectory().resolve(output), 160, 284, shade, false);
                        } else if ("cover.png".equals(output)) {
                            writeImage(command.workDirectory().resolve(output), 160, 284, 80, solidCover);
                        } else if ("cover-thumb.png".equals(output)) {
                            writeImage(command.workDirectory().resolve(output), 1080, 1080, 80, solidThumb);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return new ProductionProcessVo(0, false, false, "", "");
                }
            };
        }

        private static void writeImage(Path path, int width, int height, int shade, boolean solid) throws Exception {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(new Color(shade, shade, shade));
            graphics.fillRect(0, 0, width, height);
            if (!solid) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
                graphics.setColor(Color.RED);
                graphics.fillRect(width / 8, height / 8, width / 8, height / 8);
            }
            graphics.dispose();
            ImageIO.write(image, "png", path.toFile());
        }
    }
}
