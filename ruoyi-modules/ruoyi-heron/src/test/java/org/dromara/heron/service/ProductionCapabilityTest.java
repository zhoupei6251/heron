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
        Files.createDirectories(tempDir.resolve("zee-rp").resolve("scripts"));
        Files.createFile(tempDir.resolve("zee-rp").resolve("scripts").resolve("proto.mjs"));
        properties.setZeeRp(tempDir.resolve("zee-rp").toString());
        Files.createFile(tempDir.resolve("hyperframes.mjs"));
        properties.setHyperframes(tempDir.resolve("hyperframes.mjs").toString());
        Files.createDirectories(tempDir.resolve("templates").resolve("news-9x16"));
        properties.setNewsTemplate(tempDir.resolve("templates").resolve("news-9x16"));
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
    @DisplayName("缺渲染器入口或新闻模板目录时报具体原因，而不是运行期才炸")
    void newsReportsMissingRendererAndTemplate() {
        properties.setHyperframes(null);
        properties.setNewsTemplate(tempDir.resolve("absent-template"));

        ProductionCapabilitiesVo.Capability news =
            new ProductionCapabilityService(context, properties).getCapabilities().news();

        assertFalse(news.configured());
        assertFalse(news.available());
        assertTrue(news.reasons().contains("tool_missing:hyperframes"), news.reasons().toString());
        assertTrue(news.reasons().contains("template_missing"), news.reasons().toString());
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

    @Test
    @DisplayName("zee-rp 按引擎根判定：没有 scripts/proto.mjs 就不算具备")
    void engineRootWithoutProtoScriptIsNotConfigured() {
        properties.setZeeRp(tempDir.resolve("empty-engine").toString());

        ProductionCapabilitiesVo.Capability prototype =
            new ProductionCapabilityService(context, properties).getCapabilities().prototype();

        assertFalse(prototype.configured());
        assertTrue(prototype.reasons().contains("tool_missing:zee-rp"), prototype.reasons().toString());
    }

    @Test
    @DisplayName("引擎根、模板与流水线齐备时原型线可用")
    void prototypeAvailableWhenEngineTemplateAndPipelinePresent() throws IOException {
        properties.setPrototypeTemplate(Files.createFile(tempDir.resolve("blank-rp9.rp")));
        GenericApplicationContext engine = new GenericApplicationContext();
        engine.registerBean("prototypeProductionPipeline", IProductionPipeline.class, () -> run -> null);
        engine.refresh();
        try {
            ProductionCapabilitiesVo.Capability prototype =
                new ProductionCapabilityService(engine, properties).getCapabilities().prototype();

            assertTrue(prototype.configured());
            assertTrue(prototype.available(), prototype.reasons().toString());
        } finally {
            engine.close();
        }
    }
}
