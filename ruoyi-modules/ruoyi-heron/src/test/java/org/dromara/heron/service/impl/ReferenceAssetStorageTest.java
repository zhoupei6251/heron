package org.dromara.heron.service.impl;

import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronReferenceAsset;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("参考素材 OSS：删除失败仍保留元数据记账")
class ReferenceAssetStorageTest {

    @TempDir
    Path tempDir;

    private final HeronProductionProperties properties = new HeronProductionProperties();
    private final OssService registry = mock(OssService.class);
    private final OssClient client = mock(OssClient.class);
    private MockedStatic<OssFactory> factory;
    private ReferenceAssetStorage storage;

    @BeforeEach
    void setUp() {
        properties.setOssConfigKey("qiniu");
        storage = new ReferenceAssetStorage(properties, registry);
        factory = mockStatic(OssFactory.class);
        factory.when(() -> OssFactory.instance("qiniu")).thenReturn(client);
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @Test
    void deletesObjectBeforeUnregisteringMetadata() {
        HeronReferenceAsset asset = new HeronReferenceAsset();
        var stored = new ReferenceAssetStorage.Stored("key", "url", 1, "hash", "video/mp4");

        storage.delete(asset, stored);

        InOrder order = inOrder(client, registry);
        order.verify(client).delete("key");
        order.verify(registry).unregisterUploaded("qiniu", "key");
    }

    @Test
    void objectKeyIsUniquePerRunToken() throws Exception {
        Path file = Files.write(tempDir.resolve("reference.mp4"), new byte[] {1, 2, 3});
        var downloaded = new ReferenceVideoDownloader.Downloaded(file, 3, "hash", "video/mp4");
        when(client.upload(anyString(), any(Path.class), any(Options.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return new PutObjectResult("https://bucket.example/" + key, key, "etag", 3);
        });
        HeronReferenceAsset first = asset("token-one");
        HeronReferenceAsset second = asset("token-two");

        var firstStored = storage.upload(first, downloaded);
        var secondStored = storage.upload(second, downloaded);

        assertNotEquals(firstStored.key(), secondStored.key());
        assertTrue(firstStored.key().contains("token-one"));
        assertTrue(secondStored.key().contains("token-two"));
    }

    private static HeronReferenceAsset asset(String token) {
        HeronReferenceAsset asset = new HeronReferenceAsset();
        asset.setPlatform("douyin");
        asset.setAwemeId("123");
        asset.setRunToken(token);
        return asset;
    }

    @Test
    void keepsMetadataWhenObjectDeleteFails() {
        HeronReferenceAsset asset = asset("token");
        var stored = new ReferenceAssetStorage.Stored("key", "url", 1, "hash", "video/mp4");
        doThrow(new IllegalStateException("oss down")).when(client).delete("key");

        assertThrows(IllegalStateException.class, () -> storage.delete(asset, stored));

        verify(registry, never()).unregisterUploaded(anyString(), anyString());
    }
}
