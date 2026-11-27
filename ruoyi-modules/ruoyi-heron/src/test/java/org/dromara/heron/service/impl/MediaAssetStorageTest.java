package org.dromara.heron.service.impl;

import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.MediaAsset;
import org.dromara.heron.service.impl.shared.media.MediaAssetStorage;
import org.dromara.heron.support.MediaAssetTestSupport;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("素材 OSS：真实图像指纹、尺寸与登记")
class MediaAssetStorageTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private final HeronProductionProperties properties = new HeronProductionProperties();
    private final OssService registry = mock(OssService.class);
    private final OssClient client = mock(OssClient.class);
    private MockedStatic<OssFactory> factory;
    private MediaAssetStorage storage;

    @BeforeAll
    static void initializeJson() {
        MediaAssetTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        properties.setOssConfigKey("qiniu");
        storage = new MediaAssetStorage(properties, registry);
        factory = mockStatic(OssFactory.class);
        factory.when(() -> OssFactory.instance("qiniu")).thenReturn(client);
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @Test
    void computesHashAndDimensionsBeforeUploadingAssetScopedObjectKey() {
        when(client.upload(anyString(), any(byte[].class), any(Options.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return new PutObjectResult("https://oss.example.test/" + key, key, "etag", PNG.length);
        });
        MockMultipartFile file = new MockMultipartFile("file", "pixel.png", "image/png", PNG);

        MediaAssetStorage.Stored first = storage.upload(7L, file);
        MediaAssetStorage.Stored second = storage.upload(8L, file);

        assertEquals(1, first.width());
        assertEquals(1, first.height());
        assertEquals("image/png", first.mediaType());
        assertEquals(PNG.length, first.size());
        assertEquals(64, first.sha256().length());
        assertEquals(first.sha256(), second.sha256());
        assertNotEquals(first.key(), second.key());
        assertEquals("heron/media/7/" + first.sha256() + ".png", first.key());
        assertEquals("heron/media/8/" + second.sha256() + ".png", second.key());
    }

    @Test
    void uploadLimitMatchesTenMiBBusinessBoundary() throws IOException {
        when(client.upload(anyString(), any(byte[].class), any(Options.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return new PutObjectResult("https://oss.example.test/" + key, key, "etag", PNG.length);
        });
        MultipartFile atLimit = mock(MultipartFile.class);
        when(atLimit.isEmpty()).thenReturn(false);
        when(atLimit.getSize()).thenReturn(10L * 1024 * 1024);
        when(atLimit.getBytes()).thenReturn(PNG);
        assertEquals(PNG.length, storage.upload(7L, atLimit).size());

        MultipartFile tooLarge = mock(MultipartFile.class);
        when(tooLarge.isEmpty()).thenReturn(false);
        when(tooLarge.getSize()).thenReturn(10L * 1024 * 1024 + 1);
        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> storage.upload(8L, tooLarge));
        assertEquals(413, error.getStatus());
        assertEquals("media_asset_too_large", error.getCode());
        verify(tooLarge, never()).getBytes();
    }

    @Test
    void rejectsInvalidImageBeforeOssUpload() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", new byte[] {1, 2, 3});

        assertThrows(HeronBusinessException.class, () -> storage.upload(7L, file));

        verifyNoInteractions(client, registry);
    }

    @Test
    void rejectsTruncatedImageWithValidHeaderBeforeOssUpload() {
        byte[] truncated = java.util.Arrays.copyOf(PNG, PNG.length - 20);
        MockMultipartFile file = new MockMultipartFile("file", "truncated.png", "image/png", truncated);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> storage.upload(7L, file));

        assertEquals("media_asset_image_invalid", error.getCode());
        verifyNoInteractions(client, registry);
    }

    @Test
    void rejectsValidPngContainerWithCorruptPixelData() {
        byte[] corrupt = PNG.clone();
        int offset = 8;
        while (offset < corrupt.length) {
            int length = java.nio.ByteBuffer.wrap(corrupt, offset, 4).getInt();
            String type = new String(corrupt, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("IDAT".equals(type)) {
                java.util.Arrays.fill(corrupt, offset + 8, offset + 8 + length, (byte) 0);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(corrupt, offset + 4, 4 + length);
                java.nio.ByteBuffer.wrap(corrupt, offset + 8 + length, 4).putInt((int) crc.getValue());
                break;
            }
            offset += 12 + length;
        }
        MockMultipartFile file = new MockMultipartFile("file", "bad-pixels.png", "image/png", corrupt);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> storage.upload(7L, file));

        assertEquals("media_asset_image_invalid", error.getCode());
        verifyNoInteractions(client, registry);
    }

    @Test
    void registersAssetProvenanceAndDeletesObjectBeforeMetadata() {
        var stored = new MediaAssetStorage.Stored("qiniu", "heron/media/a.png", "https://oss.example.test/a.png",
            PNG.length, "a".repeat(64), "image/png", 1080, 1920);
        MediaAsset asset = new MediaAsset();
        asset.setId(7L);
        asset.setName("城市");
        asset.setLicenseCode("CC_BY");
        asset.setSourceUri("https://source.example.test/city");

        storage.register(asset, stored);
        ArgumentCaptor<OssDTO> row = ArgumentCaptor.forClass(OssDTO.class);
        verify(registry).registerUploaded(row.capture());
        assertEquals("qiniu", row.getValue().getService());
        assertEquals(stored.key(), row.getValue().getFileName());
        assertTrue(row.getValue().getExt1().contains("media_asset"));
        assertTrue(row.getValue().getExt1().contains("CC_BY"));

        storage.delete(stored);

        var order = inOrder(client, registry);
        order.verify(client).delete(stored.key());
        order.verify(registry).unregisterUploaded("qiniu", stored.key());
    }

    @Test
    void missingOssConfigurationFailsExplicitly() throws IOException {
        properties.setOssConfigKey(" ");
        MockMultipartFile file = new MockMultipartFile("file", "pixel.png", "image/png", PNG);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> storage.upload(7L, file));

        assertEquals("media_asset_oss_not_configured", error.getCode());
        verifyNoInteractions(client, registry);
    }
}
