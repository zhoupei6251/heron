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
        for (String tool : List.of("node", "ffmpeg", "ffprobe", "python3", "zee-rp")) {
            Files.createFile(tempDir.resolve(tool));
        }
        properties.setNode(tempDir.resolve("node").toString());
        properties.setFfmpeg(tempDir.resolve("ffmpeg").toString());
        properties.setFfprobe(tempDir.resolve("ffprobe").toString());
        properties.setPython(tempDir.resolve("python3").toString());
        properties.setZeeRp(tempDir.resolve("zee-rp").toString());
        context = new GenericApplicationContext();
        context.registerBean("newsProductionPipeline", IProductionPipeline.class,
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
    @DisplayName("原型缺 Axure 空白模板时配置不完整")
    void prototypeReportsMissingTemplate() {
        ProductionCapabilitiesVo.Capability prototype =
            new ProductionCapabilityService(context, properties).getCapabilities().prototype();

        assertFalse(prototype.configured());
        assertFalse(prototype.available());
        assertTrue(prototype.reasons().contains("template_missing"));
        assertEquals(3, prototype.capacity());
    }

    @Test
    @DisplayName("关闭 worker 与缺少工具都报告为原因")
    void workerAndToolGapsAreReported() {
        properties.setWorkerEnabled(false);
        properties.setFfmpeg(tempDir.resolve("missing-ffmpeg").toString());

        ProductionCapabilitiesVo.Capability news =
            new ProductionCapabilityService(context, properties).getCapabilities().news();

        assertFalse(news.configured());
        assertFalse(news.available());
        assertTrue(news.reasons().contains("worker_disabled"));
        assertTrue(news.reasons().contains("tool_missing:ffmpeg"));
    }
}
