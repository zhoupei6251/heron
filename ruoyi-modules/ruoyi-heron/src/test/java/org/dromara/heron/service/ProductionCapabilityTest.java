package org.dromara.heron.service;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.vo.ProductionCapabilitiesVo;
import org.dromara.heron.service.impl.ProductionCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("能力报告：本地条件与门禁原因")
class ProductionCapabilityTest {

    @TempDir
    Path tempDir;

    private HeronProductionProperties properties;
    private GenericApplicationContext context;

    @BeforeEach
    void setUp() throws IOException {
        properties = new HeronProductionProperties();
        properties.setDataRoot(tempDir);
        properties.setWorkerEnabled(true);
        for (String tool : List.of("node", "ffmpeg", "ffprobe", "python3")) {
            Files.createFile(tempDir.resolve(tool));
        }
        properties.setNode(tempDir.resolve("node").toString());
        properties.setFfmpeg(tempDir.resolve("ffmpeg").toString());
        properties.setFfprobe(tempDir.resolve("ffprobe").toString());
        properties.setPython(tempDir.resolve("python3").toString());
        Files.createFile(tempDir.resolve("hyperframes.mjs"));
        properties.setHyperframes(tempDir.resolve("hyperframes.mjs").toString());
        Path profiles = tempDir.resolve("profiles");
        Files.createDirectories(profiles.resolve("_shared/fonts"));
        Files.createDirectories(profiles.resolve("_shared/vendor"));
        Files.createFile(profiles.resolve("_shared/fonts/NotoSansSC-Regular.otf"));
        Files.createFile(profiles.resolve("_shared/vendor/gsap.min.js"));
        properties.setNewsProfile(profiles);
        context = new GenericApplicationContext();
        context.registerBean("newsExplainerPipeline", IProductionPipeline.class,
            () -> run -> null);
        context.refresh();
    }

    @Test
    @DisplayName("工具、流水线与开关齐备时可用")
    void newsAvailableWhenEverythingPresent() {
        ProductionCapabilitiesVo.Capability news =
            new ProductionCapabilityService(context, properties).getCapabilities().news();

        assertTrue(news.configured());
        assertTrue(news.available());
        assertTrue(news.reasons().isEmpty());
        assertEquals(1, news.capacity());
    }

    @Test
    @DisplayName("缺渲染器入口或 Profile 共用资产时报具体原因")
    void newsReportsMissingRendererAndProfiles() {
        properties.setHyperframes(null);
        properties.setNewsProfile(tempDir.resolve("absent-profiles"));

        ProductionCapabilitiesVo.Capability news =
            new ProductionCapabilityService(context, properties).getCapabilities().news();

        assertFalse(news.configured());
        assertFalse(news.available());
        assertTrue(news.reasons().contains("tool_missing:hyperframes"), news.reasons().toString());
        assertTrue(news.reasons().contains("profile_assets_missing"), news.reasons().toString());
    }

    @Test
    @DisplayName("缺少流水线 bean 时不可用并给出原因")
    void missingPipelineIsReported() {
        GenericApplicationContext empty = new GenericApplicationContext();
        empty.refresh();
        try {
            ProductionCapabilitiesVo.Capability news =
                new ProductionCapabilityService(empty, properties).getCapabilities().news();

            assertFalse(news.available());
            assertTrue(news.reasons().contains("pipeline_missing"));
        } finally {
            empty.close();
        }
    }

    @Test
    @DisplayName("音乐视频线还没有流水线，能力项给出 pipeline_missing")
    void musicVideoReportsMissingPipeline() {
        ProductionCapabilitiesVo.Capability musicVideo =
            new ProductionCapabilityService(context, properties).getCapabilities().musicVideo();

        assertFalse(musicVideo.configured());
        assertFalse(musicVideo.available());
        assertTrue(musicVideo.reasons().contains("pipeline_missing"), musicVideo.reasons().toString());
        assertEquals(0, musicVideo.capacity());
    }
}
