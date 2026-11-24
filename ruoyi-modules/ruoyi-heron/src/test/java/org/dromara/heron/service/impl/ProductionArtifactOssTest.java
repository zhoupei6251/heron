package org.dromara.heron.service.impl;

import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.exception.S3StorageException;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.GetObjectResult;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionArtifactOssTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path outsideDir;

    private HeronProductionProperties properties;
    private RecordingOssService registry;
    private ProductionArtifactService service;
    private OssClient client;
    private MockedStatic<OssFactory> factory;

    @BeforeEach
    void setUp() {
        properties = new HeronProductionProperties();
        properties.setDataRoot(tempDir);
        properties.setOssConfigKey("qiniu");
        registry = new RecordingOssService();
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
        service = new ProductionArtifactService(properties, registry, clock);
        client = mock(OssClient.class);
        factory = mockStatic(OssFactory.class);
        factory.when(() -> OssFactory.instance("qiniu")).thenReturn(client);
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @Test
    void registersUploadedArtifactOnlyWhenVersionIsCommitted() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        assertTrue(registry.registered.isEmpty());

        service.register(run, List.of(artifact));

        assertEquals(1, registry.registered.size());
        OssDTO row = registry.registered.get(artifact.storageKey());
        assertEquals("qiniu", row.getService());
        assertEquals(artifact.storageKey(), row.getFileName());
        assertTrue(row.getOriginalName().endsWith(".json"));
        assertEquals(".json", row.getFileSuffix());
        assertEquals("https://bucket.example/" + artifact.storageKey(), row.getUrl());
        JsonNode ext = JsonMapper.builder().build().readTree(row.getExt1());
        assertEquals("heron", ext.path("source").asString());
        assertEquals("news_video", ext.path("bizType").asString());
        assertEquals("10", ext.path("refId").asString());
        assertEquals(artifact.bytes(), ext.path("fileSize").asLong());
        assertEquals("application/json", ext.path("contentType").asString());
        assertEquals(false, ext.path("isTemp").asBoolean());
    }

    @Test
    void failedAttemptDeletesBucketObjectWithoutRegisteringMetadata() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);

        service.releaseAttempt(run, false);

        verify(client).delete(artifact.storageKey());
        assertTrue(registry.registered.isEmpty());
    }

    @Test
    void failedUploadIsIncludedInAttemptCleanup() throws IOException {
        ProductionRunVo run = run();
        Path work = service.prepareWorkDirectory(run);
        Path file = Files.writeString(work.resolve("script.json"), "{}");
        AtomicReference<String> uploadedKey = new AtomicReference<>();
        when(client.upload(anyString(), any(Path.class), any(Options.class))).thenAnswer(invocation -> {
            uploadedKey.set(invocation.getArgument(0));
            throw new S3StorageException("upload failed");
        });

        assertThrows(HeronBusinessException.class,
            () -> service.finalizeFile(run, "script", file, "application/json"));
        service.releaseAttempt(run, false);

        verify(client).delete(uploadedKey.get());
        assertFalse(Files.exists(tempDir.resolve(uploadedKey.get())));
    }

    @Test
    void cleanupUnregistersMetadataWhenBucketObjectIsAlreadyGone() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        service.register(run, List.of(artifact));
        doReturn(null).when(client).download(anyString(),
            org.mockito.ArgumentMatchers.<BiFunction<GetObjectResult, InputStream, GetObjectResult>>any());

        service.cleanupFile(artifact);

        assertEquals(List.of(artifact.storageKey()), registry.unregistered);
        assertFalse(Files.exists(tempDir.resolve(artifact.storageKey())));
        verify(client, never()).delete(anyString());
    }

    @Test
    void cleanupDeletesBucketMetadataAndLocalCopy() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        service.register(run, List.of(artifact));
        stubHead(artifact);

        service.cleanupFile(artifact);

        verify(client).delete(artifact.storageKey());
        assertEquals(List.of(artifact.storageKey()), registry.unregistered);
        assertFalse(Files.exists(tempDir.resolve(artifact.storageKey())));
    }

    @Test
    void bucketDeleteFailureKeepsMetadataAndLocalCopyForRetry() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        service.register(run, List.of(artifact));
        stubHead(artifact);
        when(client.delete(artifact.storageKey())).thenThrow(new S3StorageException("bucket down"));

        assertThrows(S3StorageException.class, () -> service.cleanupFile(artifact));

        assertTrue(registry.unregistered.isEmpty());
        assertTrue(Files.exists(tempDir.resolve(artifact.storageKey())));
    }

    @Test
    void cloudRetentionDeletesExpiredObjectAndMetadata() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        service.register(run, List.of(artifact));
        registry.expired.add(artifact.storageKey());
        stubHead(artifact);

        assertEquals(1, service.cleanupExpiredUploads(Duration.ofDays(90)));

        verify(client).delete(artifact.storageKey());
        assertEquals(List.of(artifact.storageKey()), registry.unregistered);
        assertEquals("qiniu", registry.expiredService);
        assertEquals("heron", registry.expiredSource);
        assertEquals(LocalDateTime.of(2026, 8, 26, 0, 0), registry.expiredCutoff);
        assertEquals(200, registry.expiredLimit);
    }

    @Test
    void cloudRetentionUnregistersMetadataWhenObjectIsAlreadyGone() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        service.register(run, List.of(artifact));
        registry.expired.add(artifact.storageKey());
        doReturn(null).when(client).download(anyString(),
            org.mockito.ArgumentMatchers.<BiFunction<GetObjectResult, InputStream, GetObjectResult>>any());

        assertEquals(1, service.cleanupExpiredUploads(Duration.ofDays(90)));

        verify(client, never()).delete(anyString());
        assertEquals(List.of(artifact.storageKey()), registry.unregistered);
    }

    @Test
    void cloudRetentionKeepsMetadataWhenDeleteFailsAndRetriesNextRun() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        service.register(run, List.of(artifact));
        registry.expired.add(artifact.storageKey());
        stubHead(artifact);
        when(client.delete(artifact.storageKey()))
            .thenThrow(new S3StorageException("bucket down"))
            .thenReturn(true);

        assertEquals(0, service.cleanupExpiredUploads(Duration.ofDays(90)));
        assertTrue(registry.registered.containsKey(artifact.storageKey()));
        assertTrue(registry.unregistered.isEmpty());

        assertEquals(1, service.cleanupExpiredUploads(Duration.ofDays(90)));
        assertFalse(registry.registered.containsKey(artifact.storageKey()));
        assertEquals(List.of(artifact.storageKey()), registry.unregistered);
    }

    @Test
    void failedBucketDeleteKeepsTemporaryMetadataForRetry() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        when(client.delete(artifact.storageKey())).thenThrow(new S3StorageException("bucket down"));

        service.releaseAttempt(run, false);

        assertTrue(registry.registered.containsKey(artifact.storageKey()));
        JsonNode ext = JsonMapper.builder().build().readTree(registry.registered.get(artifact.storageKey()).getExt1());
        assertEquals(true, ext.path("isTemp").asBoolean());
        assertTrue(registry.unregistered.isEmpty());
        assertFalse(Files.exists(tempDir.resolve(artifact.storageKey())));
    }

    @Test
    void failedAttemptDoesNotDeleteThroughSymlinkedParent() throws IOException {
        ProductionRunVo run = run();
        ProductionArtifactVo artifact = finalize(run);
        Path videos = tempDir.resolve("videos");
        Files.move(videos, tempDir.resolve("videos-real"));
        try {
            Files.createSymbolicLink(videos, outsideDir);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, e.getMessage());
        }
        Path outsideFile = outsideDir.resolve(artifact.storageKey().substring("videos/".length()));
        Files.createDirectories(outsideFile.getParent());
        Files.writeString(outsideFile, "outside");
        try {
            service.releaseAttempt(run, false);
            assertTrue(Files.exists(outsideFile));
        } finally {
            Files.deleteIfExists(videos);
        }
    }

    private void stubHead(ProductionArtifactVo artifact) {
        GetObjectResult head = new GetObjectResult(artifact.storageKey(), "etag", null, artifact.bytes(),
            artifact.mediaType(), null, null, null, null, Map.of("sha256", artifact.sha256()));
        doReturn(head).when(client).download(eq(artifact.storageKey()),
            org.mockito.ArgumentMatchers.<BiFunction<GetObjectResult, InputStream, GetObjectResult>>any());
    }

    private ProductionArtifactVo finalize(ProductionRunVo run) throws IOException {
        Path work = service.prepareWorkDirectory(run);
        Path file = Files.writeString(work.resolve("script.json"), "{}");
        when(client.upload(anyString(), any(Path.class), any(Options.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Path path = invocation.getArgument(1);
            return new PutObjectResult("https://bucket.example/" + key, key, "etag", Files.size(path));
        });
        return service.finalizeFile(run, "script", file, "application/json");
    }

    private ProductionRunVo run() {
        return new ProductionRunVo(9001L, ProductionBizType.news_video, 10L, 5L, 1,
            UUID.randomUUID().toString(), "{}", null);
    }

    private static final class RecordingOssService implements OssService {
        private final Map<String, OssDTO> registered = new LinkedHashMap<>();
        private final List<String> unregistered = new ArrayList<>();
        private final List<String> expired = new ArrayList<>();
        private String expiredService;
        private String expiredSource;
        private LocalDateTime expiredCutoff;
        private int expiredLimit;

        @Override
        public String selectUrlByIds(String ossIds) {
            return null;
        }

        @Override
        public List<OssDTO> selectByIds(String ossIds) {
            return List.of();
        }

        @Override
        public void registerUploaded(OssDTO uploaded) {
            registered.put(uploaded.getFileName(), uploaded);
        }

        @Override
        public void unregisterUploaded(String service, String fileName) {
            unregistered.add(fileName);
            registered.remove(fileName);
        }

        @Override
        public List<String> listUploadedKeysBefore(String service, String source, LocalDateTime cutoff, int limit) {
            expiredService = service;
            expiredSource = source;
            expiredCutoff = cutoff;
            expiredLimit = limit;
            return expired;
        }
    }
}
