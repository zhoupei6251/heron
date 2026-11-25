package org.dromara.heron.service.impl;

import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.MediaAsset;
import org.dromara.heron.domain.bo.MediaAssetBo;
import org.dromara.heron.domain.vo.MediaAssetVo;
import org.dromara.heron.mapper.MediaAssetMapper;
import org.dromara.heron.support.MediaAssetTestSupport;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("素材入库：许可先于 OSS，元数据与对象存储一致")
class MediaAssetServiceImplTest {

    private final MediaAssetMapper assets = mock(MediaAssetMapper.class);
    private final MediaAssetStorage storage = mock(MediaAssetStorage.class);
    private final MediaAssetServiceImpl service = new MediaAssetServiceImpl(assets, storage);

    @BeforeAll
    static void initializeConversion() {
        MediaAssetTestSupport.initialize();
    }

    @Test
    void ccLicenseRequiresAttributionBeforeStorage() {
        MediaAssetBo bo = metadata();
        bo.setAttribution(" ");

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.insertByBo(bo, file()));

        assertEquals("media_asset_attribution_required", error.getCode());
        verifyNoInteractions(storage, assets);
    }

    @Test
    void unknownLicenseIsRejectedBeforeReadingOrUploadingFile() {
        MediaAssetBo bo = metadata();
        bo.setLicenseCode("UNKNOWN");

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.insertByBo(bo, file()));

        assertEquals("media_asset_license_unknown", error.getCode());
        verifyNoInteractions(storage, assets);
    }

    @Test
    void ingestionPersistsServerComputedDimensionsHashAndOssPointer() {
        MediaAssetBo bo = metadata();
        var stored = new MediaAssetStorage.Stored("qiniu", "heron/media/hash.png", "https://oss.example.test/hash.png",
            4L, "a".repeat(64), "image/png", 1080, 1920);
        when(storage.upload(anyLong(), any())).thenReturn(stored);
        when(assets.insert(any(MediaAsset.class))).thenAnswer(invocation -> {
            MediaAsset row = invocation.getArgument(0);
            assertEquals("IMAGE", row.getAssetKind());
            assertEquals("CC_BY", row.getLicenseCode());
            assertEquals(1080, row.getWidth());
            assertEquals(1920, row.getHeight());
            assertEquals("PORTRAIT", row.getOrientation());
            assertEquals(stored.sha256(), row.getContentSha256());
            assertEquals(stored.key(), row.getOssKey());
            assertEquals(stored.url(), row.getOssUrl());
            assertEquals("0", row.getDelFlag());
            return 1;
        });
        MediaAssetVo vo = new MediaAssetVo();
        vo.setId(9L);
        when(assets.selectVoById(anyLong())).thenReturn(vo);

        MediaAssetVo result = service.insertByBo(bo, file());

        assertEquals(9L, result.getId());
        verify(storage).register(any(), same(stored));
    }

    @Test
    void databaseFailureCleansUploadedObjectAndKeepsOriginalError() {
        var stored = new MediaAssetStorage.Stored("qiniu", "key", "url", 4L, "a".repeat(64), "image/png", 1, 1);
        when(storage.upload(anyLong(), any())).thenReturn(stored);
        when(assets.insert(any(MediaAsset.class))).thenThrow(new IllegalStateException("db down"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.insertByBo(metadata(), file()));

        assertEquals("db down", error.getMessage());
        verify(storage).delete(stored);
    }

    @Test
    void failedSecondIngestionOfSameContentKeepsExistingAssetObject() {
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setOssConfigKey("qiniu");
        OssService registry = mock(OssService.class);
        OssClient client = mock(OssClient.class);
        Set<String> objects = new HashSet<>();
        when(client.upload(anyString(), any(byte[].class), any(Options.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            objects.add(key);
            return new PutObjectResult("https://oss.example.test/" + key, key, "etag", png.length);
        });
        doAnswer(invocation -> {
            objects.remove(invocation.getArgument(0));
            return null;
        }).when(client).delete(anyString());
        MediaAssetStorage realStorage = new MediaAssetStorage(properties, registry);
        MediaAssetServiceImpl realService = new MediaAssetServiceImpl(assets, realStorage);
        MediaAssetVo vo = new MediaAssetVo();
        when(assets.selectVoById(anyLong())).thenReturn(vo);
        when(assets.insert(any(MediaAsset.class))).thenReturn(1).thenThrow(new IllegalStateException("db down"));

        String existingKey;
        try (MockedStatic<OssFactory> factory = mockStatic(OssFactory.class)) {
            factory.when(() -> OssFactory.instance("qiniu")).thenReturn(client);
            realService.insertByBo(metadata(), new MockMultipartFile("file", "city.png", "image/png", png));
            ArgumentCaptor<MediaAsset> first = ArgumentCaptor.forClass(MediaAsset.class);
            verify(assets).insert(first.capture());
            existingKey = first.getValue().getOssKey();

            assertThrows(IllegalStateException.class,
                () -> realService.insertByBo(metadata(), new MockMultipartFile("file", "city.png", "image/png", png)));
        }

        assertEquals(Set.of(existingKey), objects);
    }

    @Test
    void readFailureAfterCommittedInsertDoesNotDeleteObject() {
        var stored = new MediaAssetStorage.Stored("qiniu", "heron/media/7/hash.png", "url", 4L,
            "a".repeat(64), "image/png", 1, 1);
        when(storage.upload(anyLong(), any())).thenReturn(stored);
        when(assets.insert(any(MediaAsset.class))).thenReturn(1);
        when(assets.selectVoById(anyLong())).thenThrow(new IllegalStateException("read down"));

        assertThrows(IllegalStateException.class, () -> service.insertByBo(metadata(), file()));

        verify(storage, never()).delete(any());
    }

    @Test
    void batchDeleteValidatesAllIdsWithOneQuery() {
        List<Long> ids = List.of(7L, 8L);
        when(assets.selectByIds(ids)).thenReturn(List.of(row(7L), row(8L)));
        when(assets.deleteByIds(ids)).thenReturn(2);

        assertTrue(service.deleteWithValidByIds(ids));

        verify(assets).selectByIds(ids);
        verify(assets, never()).selectById(anyLong());
        verify(assets).deleteByIds(ids);
    }

    @Test
    void batchDeleteRejectsMissingIdBeforeDeletingAnything() {
        List<Long> ids = List.of(7L, 8L);
        when(assets.selectByIds(ids)).thenReturn(List.of(row(7L)));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.deleteWithValidByIds(ids));

        assertEquals(404, error.getStatus());
        verify(assets, never()).deleteByIds(anyCollection());
    }

    @Test
    void batchDeleteRejectsMoreThanOneHundredIdsBeforeQuerying() {
        List<Long> ids = LongStream.rangeClosed(1, 101).boxed().toList();

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.deleteWithValidByIds(ids));

        assertEquals(400, error.getStatus());
        assertEquals("media_asset_batch_too_large", error.getCode());
        verifyNoInteractions(assets);
    }

    private static MediaAsset row(long id) {
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setDelFlag("0");
        return asset;
    }

    @Test
    void updateCannotSmuggleUnknownLicenseBackIn() {
        MediaAssetBo bo = metadata();
        bo.setId(7L);
        bo.setLicenseCode("custom");

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> service.updateByBo(bo));

        assertEquals("media_asset_license_unknown", error.getCode());
        verify(assets, never()).updateById(any(MediaAsset.class));
    }

    private static MediaAssetBo metadata() {
        MediaAssetBo bo = new MediaAssetBo();
        bo.setName("城市");
        bo.setSourceUri("https://source.example.test/city");
        bo.setLicenseCode("CC_BY");
        bo.setLicenseEvidenceUri("https://license.example.test/cc-by");
        bo.setAttribution("作者");
        bo.setTags("城市, 夜景");
        return bo;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "city.png", "image/png", new byte[] {1, 2, 3, 4});
    }
}
