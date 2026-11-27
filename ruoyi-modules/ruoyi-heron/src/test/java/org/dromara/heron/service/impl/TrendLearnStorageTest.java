package org.dromara.heron.service.impl;

import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.oss.model.Options;
import org.dromara.common.oss.model.PutObjectResult;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.mapper.TrendLearnOssMapper;
import org.dromara.heron.service.impl.shared.media.ReferenceVideoDownloader;
import org.dromara.heron.service.impl.shared.research.TrendLearnStorage;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendLearnStorageTest {

    @TempDir
    Path tempDir;

    private final OssService ossService = mock(OssService.class);
    private final TrendLearnOssMapper ossMapper = mock(TrendLearnOssMapper.class);
    private final OssClient client = mock(OssClient.class);
    private MockedStatic<OssFactory> factory;
    private TrendLearnStorage storage;

    @BeforeEach
    void setUp() {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setOssConfigKey("qiniu");
        storage = new TrendLearnStorage(properties, ossService, ossMapper);
        factory = mockStatic(OssFactory.class);
        factory.when(() -> OssFactory.instance("qiniu")).thenReturn(client);
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @Test
    void uploadedVideoIsRegisteredAndResolvedToSysOssId() throws Exception {
        Path file = Files.writeString(tempDir.resolve("source.mp4"), "video");
        HeronTrendVideo video = video("123");
        when(client.upload(anyString(), any(Path.class), any(Options.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return new PutObjectResult("https://oss.example/" + key, key, "etag", 5L);
        });
        when(ossMapper.findId(eq("qiniu"), anyString())).thenReturn(900L);

        TrendLearnStorage.Stored stored = storage.uploadVideo(video,
            new ReferenceVideoDownloader.Downloaded(file, 5L, "a".repeat(64), "video/mp4"));

        assertEquals(900L, stored.ossId());
        ArgumentCaptor<OssDTO> row = ArgumentCaptor.forClass(OssDTO.class);
        verify(ossService).registerUploaded(row.capture());
        JsonNode ext = JsonMapper.builder().build().readTree(row.getValue().getExt1());
        assertEquals("trend_learn", ext.path("bizType").asString());
        assertEquals("123", ext.path("awemeId").asString());
        assertEquals("trend_video", ext.path("refType").asString());
        assertEquals("a".repeat(64), ext.path("sha256").asString());
        assertTrue(stored.key().endsWith("a".repeat(64) + ".mp4"));
    }

    @Test
    void restoresOriginalVideoFromSysOssMetadata() throws Exception {
        HeronTrendVideo video = video("123");
        video.setOssId(900L);
        OssDTO row = new OssDTO();
        row.setService("qiniu");
        row.setFileName("heron/trend-learn/douyin/123/video.mp4");
        when(ossService.selectByIds("900")).thenReturn(List.of(row));
        when(client.download(eq(row.getFileName()), any(Path.class))).thenAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.writeString(target, "restored-video");
            return null;
        });

        Path restored = storage.downloadVideo(video, tempDir.resolve("video-resume"));

        assertEquals("restored-video", Files.readString(restored));
    }

    @Test
    void restoresAllRawArtifactsFromStableOssKeys() throws Exception {
        HeronTrendVideo video = video("123");
        when(client.download(anyString(), any(Path.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Path target = invocation.getArgument(1);
            Files.writeString(target, Path.of(key).getFileName().toString());
            return null;
        });

        Path output = storage.downloadRaw(video, tempDir.resolve("raw-resume"));

        for (String name : TrendLearnStorage.RAW_FILES) {
            assertEquals(name, Files.readString(output.resolve(name)));
        }
    }

    @Test
    void rawKeysAreStableAndPathSafe() {
        HeronTrendVideo video = video("123");
        Map<String, String> first = storage.rawKeys(video);
        Map<String, String> second = storage.rawKeys(video);

        assertEquals(first, second);
        assertEquals(4, first.size());
        assertTrue(first.get("breakdown.json").endsWith("/raw/breakdown.json"));
        video.setAwemeId("../escape");
        assertThrows(HeronBusinessException.class, () -> storage.rawKeys(video));
    }

    private static HeronTrendVideo video(String awemeId) {
        HeronTrendVideo video = new HeronTrendVideo();
        video.setId(1L);
        video.setPlatform("douyin");
        video.setAwemeId(awemeId);
        return video;
    }
}
