package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronReferenceAsset;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.vo.ReferenceAssetVo;
import org.dromara.heron.mapper.HeronReferenceAssetMapper;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("参考素材：授权门禁、租约与 OSS 定稿")
class ReferenceAssetServiceImplTest {

    @TempDir
    Path tempDir;

    private final HeronReferenceAssetMapper assets = mock(HeronReferenceAssetMapper.class);
    private final HeronTrendVideoMapper videos = mock(HeronTrendVideoMapper.class);
    private final ReferenceVideoDownloader downloader = mock(ReferenceVideoDownloader.class);
    private final ReferenceAssetStorage storage = mock(ReferenceAssetStorage.class);
    private final HeronNewsProperties news = new HeronNewsProperties();

    private ReferenceAssetServiceImpl service() {
        news.setTrendMaxDurationMs(90_000);
        news.setReferenceMaxBytes(1024);
        news.setReferenceMaxTotalBytes(2048);
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(Clock.systemUTC());
        return new ReferenceAssetServiceImpl(assets, videos, downloader, storage, news, clocks);
    }

    @Test
    void unknownAuthorizationStopsBeforeDownload() {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_UNKNOWN);
        when(assets.selectById(1L)).thenReturn(asset);
        when(assets.markChecking(eq(1L), any())).thenReturn(1);
        when(assets.markPendingAuth(eq(1L), anyString(), any())).thenReturn(1);
        ReferenceAssetVo vo = new ReferenceAssetVo();
        vo.setStatus(HeronReferenceAsset.STATUS_PENDING_AUTH);
        when(assets.selectVoById(1L)).thenReturn(vo);
        when(videos.selectById(10L)).thenReturn(candidate());

        ReferenceAssetVo result = service().check(1L);

        assertEquals(HeronReferenceAsset.STATUS_PENDING_AUTH, result.getStatus());
        verify(downloader, never()).download(anyString());
        verify(storage, never()).upload(any(), any());
    }

    @Test
    void denialRequiresReasonAndMarksRestricted() {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_PENDING_AUTH, HeronReferenceAsset.LICENSE_UNKNOWN);
        when(assets.selectById(1L)).thenReturn(asset);
        when(assets.authorizeRestricted(eq(1L), eq("版权方未授权"), eq(0L), any())).thenReturn(1);
        ReferenceAssetVo vo = new ReferenceAssetVo();
        vo.setStatus(HeronReferenceAsset.STATUS_RESTRICTED);
        when(assets.selectVoById(1L)).thenReturn(vo);

        assertThrows(HeronBusinessException.class, () -> service().authorize(1L, false, " "));
        verify(assets, never()).authorizeRestricted(anyLong(), anyString(), anyLong(), any());

        assertEquals(HeronReferenceAsset.STATUS_RESTRICTED, service().authorize(1L, false, "版权方未授权").getStatus());
        verify(downloader, never()).download(anyString());
    }

    @Test
    void authorizedAcquisitionUploadsRegistersAndFinalizes() throws Exception {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        when(assets.selectById(1L)).thenReturn(asset);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(0L);
        Path file = Files.createTempFile(tempDir, "reference-", ".mp4");
        Files.write(file, new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'});
        var downloaded = new ReferenceVideoDownloader.Downloaded(file, 8, "a".repeat(64), "video/mp4");
        when(downloader.download("https://cdn.example.test/video.mp4")).thenReturn(downloaded);
        var stored = new ReferenceAssetStorage.Stored("heron/reference/douyin/123/hash.mp4",
            "https://oss.example.test/reference.mp4", 8, "a".repeat(64), "video/mp4");
        when(storage.upload(eq(asset), same(downloaded))).thenReturn(stored);
        when(assets.finalizeAcquired(eq(1L), anyString(), eq(stored.key()), eq(stored.url()), eq(8L),
            eq(stored.sha256()), eq("video/mp4"), any())).thenReturn(1);
        ReferenceAssetVo vo = new ReferenceAssetVo();
        vo.setStatus(HeronReferenceAsset.STATUS_ACQUIRED);
        when(assets.selectVoById(1L)).thenReturn(vo);

        ReferenceAssetVo result = service().acquire(1L);

        assertEquals(HeronReferenceAsset.STATUS_ACQUIRED, result.getStatus());
        verify(storage).register(eq(asset), same(stored));
        verify(assets).finalizeAcquired(eq(1L), anyString(), eq(stored.key()), eq(stored.url()), eq(8L),
            eq(stored.sha256()), eq("video/mp4"), any());
        assertFalse(Files.exists(file), "定稿后临时文件必须删除");
    }

    @Test
    void downloadFailureReleasesLeaseAndKeepsReason() {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        when(assets.selectById(1L)).thenReturn(asset);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(0L);
        when(downloader.download(anyString())).thenThrow(new HeronBusinessException(502,
            "reference_download_auth", "来源要求登录"));
        when(assets.recordFailure(eq(1L), anyString(), eq("reference_download_auth"), contains("登录"), any()))
            .thenReturn(1);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> service().acquire(1L));

        assertEquals("reference_download_auth", error.getCode());
        verify(assets).recordFailure(eq(1L), anyString(), eq("reference_download_auth"), contains("登录"), any());
        verify(storage, never()).upload(any(), any());
    }

    @Test
    void capacityReservationBlocksBeforeNetwork() {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        when(assets.selectById(1L)).thenReturn(asset);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(2049L);
        when(assets.recordFailure(eq(1L), anyString(), eq("reference_capacity_exceeded"), anyString(), any()))
            .thenReturn(1);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> service().acquire(1L));

        assertEquals("reference_capacity_exceeded", error.getCode());
        verify(downloader, never()).download(anyString());
    }

    @Test
    void staleTokenCannotFinalizeAndUploadedObjectIsRemoved() throws Exception {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        when(assets.selectById(1L)).thenReturn(asset);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(0L);
        Path file = Files.createTempFile(tempDir, "reference-", ".mp4");
        var downloaded = new ReferenceVideoDownloader.Downloaded(file, 0, "b".repeat(64), "video/mp4");
        when(downloader.download(anyString())).thenReturn(downloaded);
        var stored = new ReferenceAssetStorage.Stored("key", "url", 0, "b".repeat(64), "video/mp4");
        when(storage.upload(eq(asset), same(downloaded))).thenReturn(stored);
        when(assets.finalizeAcquired(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyString(), any())).thenReturn(0);

        HeronBusinessException error = assertThrows(HeronBusinessException.class, () -> service().acquire(1L));

        assertEquals("reference_lease_lost", error.getCode());
        verify(storage).delete(eq(asset), same(stored));
    }

    @Test
    void expiredAcquiringLeaseCanBeTakenOver() throws Exception {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_ACQUIRING, HeronReferenceAsset.LICENSE_ALLOWED);
        asset.setLeaseUntil(LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        when(assets.selectById(1L)).thenReturn(asset);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(0L);
        Path file = Files.createTempFile(tempDir, "reference-", ".mp4");
        var downloaded = new ReferenceVideoDownloader.Downloaded(file, 0, "d".repeat(64), "video/mp4");
        when(downloader.download(anyString())).thenReturn(downloaded);
        var stored = new ReferenceAssetStorage.Stored("key", "url", 0, "d".repeat(64), "video/mp4");
        when(storage.upload(eq(asset), same(downloaded))).thenReturn(stored);
        when(assets.finalizeAcquired(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyString(), any())).thenReturn(1);
        ReferenceAssetVo vo = new ReferenceAssetVo();
        vo.setStatus(HeronReferenceAsset.STATUS_ACQUIRED);
        when(assets.selectVoById(1L)).thenReturn(vo);

        assertEquals(HeronReferenceAsset.STATUS_ACQUIRED, service().acquire(1L).getStatus());
    }

    @Test
    void claimFailureDoesNotLeakDownloadPermit() throws Exception {
        news.setReferenceMaxConcurrent(1);
        HeronReferenceAsset first = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        HeronReferenceAsset second = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        second.setId(2L);
        second.setTrendId(20L);
        second.setAwemeId("456");
        HeronTrendVideo secondCandidate = candidate();
        secondCandidate.setId(20L);
        secondCandidate.setAwemeId("456");
        when(assets.selectById(1L)).thenReturn(first);
        when(assets.selectById(2L)).thenReturn(second);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(videos.selectById(20L)).thenReturn(secondCandidate);
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(0);
        when(assets.claim(eq(2L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(0L);
        Path file = Files.createTempFile(tempDir, "reference-", ".mp4");
        var downloaded = new ReferenceVideoDownloader.Downloaded(file, 0, "e".repeat(64), "video/mp4");
        when(downloader.download(anyString())).thenReturn(downloaded);
        var stored = new ReferenceAssetStorage.Stored("key", "url", 0, "e".repeat(64), "video/mp4");
        when(storage.upload(eq(second), same(downloaded))).thenReturn(stored);
        when(assets.finalizeAcquired(eq(2L), anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyString(), any())).thenReturn(1);
        ReferenceAssetVo vo = new ReferenceAssetVo();
        vo.setStatus(HeronReferenceAsset.STATUS_ACQUIRED);
        when(assets.selectVoById(2L)).thenReturn(vo);
        var subject = service();

        assertThrows(HeronBusinessException.class, () -> subject.acquire(1L));
        assertEquals(HeronReferenceAsset.STATUS_ACQUIRED, subject.acquire(2L).getStatus());
    }

    @Test
    void finalizedAssetIsNotDeletedWhenReadbackFails() throws Exception {
        HeronReferenceAsset asset = asset(HeronReferenceAsset.STATUS_DISCOVERED, HeronReferenceAsset.LICENSE_ALLOWED);
        when(assets.selectById(1L)).thenReturn(asset);
        when(videos.selectById(10L)).thenReturn(candidate());
        when(assets.claim(eq(1L), anyString(), any(), any(), eq(1024L))).thenReturn(1);
        when(assets.usedBytes()).thenReturn(0L);
        Path file = Files.createTempFile(tempDir, "reference-", ".mp4");
        var downloaded = new ReferenceVideoDownloader.Downloaded(file, 0, "c".repeat(64), "video/mp4");
        when(downloader.download(anyString())).thenReturn(downloaded);
        var stored = new ReferenceAssetStorage.Stored("key", "url", 0, "c".repeat(64), "video/mp4");
        when(storage.upload(eq(asset), same(downloaded))).thenReturn(stored);
        when(assets.finalizeAcquired(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyString(), any())).thenReturn(1);
        when(assets.selectVoById(1L)).thenThrow(new HeronBusinessException(404, "read_failed", "回读失败"));

        assertThrows(HeronBusinessException.class, () -> service().acquire(1L));

        verify(storage, never()).delete(any(), any());
    }

    @Test
    void registrationSkipsUnknownDurationAndExistingAssets() {
        HeronTrendVideo unknown = candidate();
        unknown.setAwemeId("unknown");
        unknown.setDurationMs(null);
        HeronTrendVideo existing = candidate();
        existing.setAwemeId("existing");
        when(assets.findByPlatformAndAweme("douyin", "existing")).thenReturn(
            asset(HeronReferenceAsset.STATUS_PENDING_AUTH, HeronReferenceAsset.LICENSE_UNKNOWN));

        service().registerFromTrend(List.of(unknown, existing));

        verify(assets, never()).findByPlatformAndAweme("douyin", "unknown");
        verify(assets, never()).insert(any(HeronReferenceAsset.class));
    }

    private static HeronReferenceAsset asset(String status, String license) {
        HeronReferenceAsset asset = new HeronReferenceAsset();
        asset.setId(1L);
        asset.setTrendId(10L);
        asset.setPlatform("douyin");
        asset.setAwemeId("123");
        asset.setStatus(status);
        asset.setLicenseStatus(license);
        return asset;
    }

    private static HeronTrendVideo candidate() {
        HeronTrendVideo video = new HeronTrendVideo();
        video.setId(10L);
        video.setPlatform("douyin");
        video.setAwemeId("123");
        video.setDurationMs(30_000L);
        video.setDownloadUrl("https://cdn.example.test/video.mp4");
        return video;
    }
}
