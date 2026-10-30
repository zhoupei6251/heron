package org.dromara.heron.service;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("受控进程：参数边界与 Linux 监护")
class ProductionProcessTest {

    @TempDir
    Path tempDir;

    private ProductionProcessService service;
    private ProductionArtifactService artifacts;

    @BeforeEach
    void setUp() {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(tempDir);
        artifacts = new ProductionArtifactService(properties);
        service = new ProductionProcessService(properties, artifacts);
    }

    @Test
    @DisplayName("工具、参数与超时边界在启动前拒绝")
    void rejectsInvalidCommandsBeforeStart() {
        ProductionRunVo run = run();

        HeronBusinessException tool = assertThrows(HeronBusinessException.class, () ->
            service.run(run, new ProductionProcessBo("bash", List.of("-c", "rm -rf /"), null, null)));
        HeronBusinessException arguments = assertThrows(HeronBusinessException.class, () ->
            service.run(run, new ProductionProcessBo("node", List.of("a\nb"), null, null)));
        HeronBusinessException timeout = assertThrows(HeronBusinessException.class, () ->
            service.run(run, new ProductionProcessBo("node", List.of("x"), null, Duration.ofSeconds(300))));

        assertEquals("production_invalid_tool", tool.getCode());
        assertEquals("production_invalid_arguments", arguments.getCode());
        assertEquals("production_invalid_timeout", timeout.getCode());
    }

    @Test
    @DisplayName("外部工作目录不可冒用本次尝试目录")
    void rejectsForeignWorkDirectory() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            service.run(run(), new ProductionProcessBo("node", List.of("x"),
                tempDir.resolve("other"), Duration.ofSeconds(10))));

        assertEquals("production_invalid_workdir", error.getCode());
    }

    @Test
    @Tag("process")
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux：子进程正常退出并回收输出")
    void runsChildOnLinux() {
        ProductionProcessVo result = service.run(run(), new ProductionProcessBo(
            "python", List.of("-c", "print('heron-ok')"), null, Duration.ofSeconds(20)));

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.stdout().contains("heron-ok"));
    }

    @Test
    @Tag("process")
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux：超时终止整个进程组")
    void timesOutAndStopsGroupOnLinux() {
        ProductionProcessVo result = service.run(run(), new ProductionProcessBo(
            "python", List.of("-c", "import time; time.sleep(60)"), null, Duration.ofSeconds(1)));

        assertTrue(result.timedOut());
        assertEquals(124, result.exitCode());
    }

    @Test
    @Tag("process")
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux：超时后派生的孙子进程不再写标记")
    void timeoutKillsDerivedGrandchildOnLinux() throws Exception {
        ProductionRunVo run = run();
        Path fixture = Path.of("src/test/resources/process-fixtures/child.py").toAbsolutePath();
        String marker = "late.marker";

        ProductionProcessVo result = service.run(run, new ProductionProcessBo(
            "python", List.of(fixture.toString(), marker), null, Duration.ofSeconds(1)));

        assertTrue(result.timedOut());
        Thread.sleep(3500);
        assertFalse(java.nio.file.Files.exists(artifacts.prepareWorkDirectory(run).resolve(marker)));
    }

    @Test
    @Tag("process")
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux：输出超过 1MiB 直接失败")
    void rejectsOversizedOutputOnLinux() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            service.run(run(), new ProductionProcessBo(
                "python", List.of("-c", "print('x' * (2 * 1024 * 1024))"), null,
                Duration.ofSeconds(20))));

        assertEquals("production_output_limit", error.getCode());
    }

    private ProductionRunVo run() {
        return new ProductionRunVo(9001L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), "{}", null);
    }
}
