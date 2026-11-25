package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 非首屏抽帧的真实 ffmpeg 回归：区间产物从 0 开始，不能用全片绝对时间 seek。 */
@Tag("media")
@DisplayName("场景返工真 ffmpeg：非首屏片内中点抽帧")
class NewsProductionPipelineSceneReworkMediaTest {

    @Test
    @DisplayName("20–24 秒的全片窗口只从 4 秒单屏片的 2 秒处抽帧")
    void extractsMidpointFromNonFirstSceneClip(@TempDir Path root) throws Exception {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(root.resolve("data"));
        properties.setGuardEnabled(false);
        ProductionArtifactService artifacts = new ProductionArtifactService(properties, null);
        RecordingProcesses processes = new RecordingProcesses(properties, artifacts);
        NewsProductionPipeline pipeline = new NewsProductionPipeline(null, null, processes, artifacts, properties,
            (run, stage, progress) -> true, null, null, null, null, null, null, null);
        ProductionRunVo run = new ProductionRunVo(7201L, ProductionBizType.news_explainer, 10L, 4L, 1,
            UUID.randomUUID().toString(), "{}");
        Path work = artifacts.prepareWorkDirectory(run);
        ProductionProcessVo created = processes.run(run, new ProductionProcessBo("ffmpeg", List.of(
            "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x284:rate=30:duration=4",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "scene.mp4"), work, java.time.Duration.ofMinutes(2)));
        assertEquals(0, created.exitCode(), created.stderr());

        Path frame = pipeline.sceneFrame(run, work,
            new NewsNarrationTiming.Span("scene_001", 20_000, 24_000, false));

        assertTrue(Files.size(frame) > 0, "真实 ffmpeg 必须产出非首屏中点帧");
        assertNotNull(ImageIO.read(frame.toFile()), "抽出的帧必须可解码");
        assertEquals("2.000", processes.lastFrameSeek, "seek 必须使用单屏片内中点，而不是全片绝对时间 22 秒");
    }

    private static final class RecordingProcesses extends ProductionProcessService {
        private final List<List<String>> ffmpegArguments = new ArrayList<>();
        private String lastFrameSeek;

        private RecordingProcesses(HeronProductionProperties properties, ProductionArtifactService artifacts) {
            super(properties, artifacts);
        }

        @Override
        public ProductionProcessVo run(ProductionRunVo run, ProductionProcessBo command) {
            if ("ffmpeg".equals(command.tool())) {
                ffmpegArguments.add(List.copyOf(command.arguments()));
                if (command.arguments().contains("scene-frame.png")) {
                    lastFrameSeek = command.arguments().get(command.arguments().indexOf("-ss") + 1);
                }
            }
            return super.run(run, command);
        }
    }
}
