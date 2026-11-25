package org.dromara.heron.service;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("产物定稿：同卷原子移动、边界校验与哈希")
class ProductionArtifactTest {

    @TempDir
    Path tempDir;

    private HeronProductionProperties properties;
    private ProductionArtifactService service;

    @BeforeEach
    void setUp() {
        properties = new HeronProductionProperties();
        properties.setDataRoot(tempDir);
        service = new ProductionArtifactService(properties, null);
    }

    @Test
    @DisplayName("尝试目录内的产物原子定稿并通过校验")
    void finalizeMovesAtomicallyAndVerifies() throws IOException {
        ProductionRunVo run = run(ProductionBizType.news_explainer);
        Path work = service.prepareWorkDirectory(run);
        Path file = Files.writeString(work.resolve("script.json"), "{\"scenes\":1}");

        ProductionArtifactVo artifact = service.finalizeFile(run, "script", file, "application/json");

        assertFalse(Files.exists(file));
        assertTrue(artifact.storageKey().startsWith("videos/10/v5/a1-"));
        assertTrue(artifact.storageKey().endsWith(".json"));
        assertEquals(12, artifact.bytes());
        assertTrue(Files.isRegularFile(tempDir.resolve(artifact.storageKey())));
        service.verify(artifact);
    }

    @Test
    @DisplayName("尝试目录外的文件不可定稿")
    void rejectsFileOutsideAttemptDirectory() throws IOException {        ProductionRunVo run = run(ProductionBizType.news_explainer);
        service.prepareWorkDirectory(run);
        Path outside = Files.writeString(tempDir.resolve("escape.json"), "{}");

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () ->
            service.finalizeFile(run, "script", outside, "application/json"));

        assertEquals(400, error.getStatus());
        assertEquals("production_invalid_artifact", error.getCode());
    }

    @Test
    @DisplayName("空文件与超过单文件上限的文件都拒绝")
    void rejectsEmptyAndOversizeFiles() throws IOException {
        properties.setNewsMaxBytes(4);
        ProductionRunVo run = run(ProductionBizType.news_explainer);
        Path work = service.prepareWorkDirectory(run);
        Path empty = Files.createFile(work.resolve("empty.json"));
        Path oversize = Files.writeString(work.resolve("big.json"), "12345");

        HeronBusinessException emptyError = assertThrows(HeronBusinessException.class, () ->
            service.finalizeFile(run, "script", empty, "application/json"));
        HeronBusinessException sizeError = assertThrows(HeronBusinessException.class, () ->
            service.finalizeFile(run, "script", oversize, "application/json"));

        assertEquals("production_file_invalid", emptyError.getCode());
        assertEquals("production_file_invalid", sizeError.getCode());
        assertTrue(Files.exists(empty));
    }

    @Test
    @DisplayName("文件被改写后校验失败且不可删除")
    void detectsTamperedFile() throws IOException {
        ProductionRunVo run = run(ProductionBizType.news_explainer);
        Path work = service.prepareWorkDirectory(run);
        Path file = Files.writeString(work.resolve("video.mp4"), "payload");
        ProductionArtifactVo artifact = service.finalizeFile(run, "video", file, "video/mp4");
        Files.writeString(tempDir.resolve(artifact.storageKey()), "tampered");

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.verify(artifact));

        assertEquals(409, error.getStatus());
        assertEquals("production_file_corrupt", error.getCode());
        assertThrows(HeronBusinessException.class, () -> service.removeFile(artifact));
        assertTrue(Files.exists(tempDir.resolve(artifact.storageKey())));
    }

    @Test
    @DisplayName("存储键穿越与绝对路径被拒绝")
    void rejectsTraversalStorageKey() {
        ProductionArtifactVo traversal = new ProductionArtifactVo(
            UUID.randomUUID().toString(), "script", "../escape.json", 2, "00", "application/json");

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.verify(traversal));

        assertEquals("production_file_missing", error.getCode());
    }

    @Test
    @DisplayName("失败证据进入 .keep 且不进入成功产物目录")
    void failureEvidenceStaysUnderKeep() throws IOException {
        ProductionRunVo run = run(ProductionBizType.news_explainer);
        Path work = service.prepareWorkDirectory(run);
        Path file = Files.writeString(work.resolve("stderr.log"), "boom");

        String key = service.keepEvidence(run, file);

        assertTrue(key.contains("/.keep/"));
        assertTrue(key.endsWith(".log"));
        assertFalse(Files.exists(file));
        assertTrue(Files.isRegularFile(tempDir.resolve(key)));
        assertFalse(key.startsWith("videos/"));
    }

    @Test
    @DisplayName("校验通过的产物可内部清理")
    void removesVerifiedArtifact() throws IOException {
        ProductionRunVo run = run(ProductionBizType.news_explainer);
        Path work = service.prepareWorkDirectory(run);
        Path file = Files.writeString(work.resolve("script.json"), "{}");
        ProductionArtifactVo artifact = service.finalizeFile(run, "script", file, "application/json");

        service.removeFile(artifact);

        assertFalse(Files.exists(tempDir.resolve(artifact.storageKey())));
        assertFalse(service.exists(artifact), "清理续跑靠这个探针，不能假设本地路径还在");
    }

    @Test
    @DisplayName("尝试没落库就回收已定稿产物，落库了只清记账")
    void releasesUnlandedAttemptArtifacts() throws IOException {
        ProductionRunVo landed = run(ProductionBizType.news_explainer);
        ProductionArtifactVo kept = service.finalizeFile(landed, "script",
            Files.writeString(service.prepareWorkDirectory(landed).resolve("a.json"), "{}"), "application/json");
        service.releaseAttempt(landed, true);
        assertTrue(Files.exists(tempDir.resolve(kept.storageKey())), "落库成功不能碰产物");

        ProductionRunVo failed = new ProductionRunVo(9002L, ProductionBizType.news_explainer, 10L, 5L, 1,
            UUID.randomUUID().toString(), null);
        ProductionArtifactVo dropped = service.finalizeFile(failed, "script",
            Files.writeString(service.prepareWorkDirectory(failed).resolve("b.json"), "{}"), "application/json");
        service.releaseAttempt(failed, false);
        assertFalse(Files.exists(tempDir.resolve(dropped.storageKey())), "失败尝试不该留下无人引用的字节");
        assertFalse(service.exists(dropped));
    }

    private ProductionRunVo run(ProductionBizType type) {
        return new ProductionRunVo(9001L, type, 10L, 5L, 1,
            UUID.randomUUID().toString(), "{}");
    }
}
