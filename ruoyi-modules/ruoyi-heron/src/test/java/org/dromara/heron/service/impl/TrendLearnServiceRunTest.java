package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendLearnState;
import org.dromara.heron.domain.TrendTechniqueCard;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendLearnServiceRunTest {

    @TempDir
    Path tempDir;

    private final HeronTrendVideoMapper videos = mock(HeronTrendVideoMapper.class);
    private final OpenAiModelClient model = mock(OpenAiModelClient.class);
    private final ReferenceVideoDownloader downloader = mock(ReferenceVideoDownloader.class);
    private final TrendLearnStorage storage = mock(TrendLearnStorage.class);
    private final TrendVideoDecomposer decomposer = mock(TrendVideoDecomposer.class);
    private final TrendLearningAgent agent = mock(TrendLearningAgent.class);
    private final TrendLearnDocuments documents = mock(TrendLearnDocuments.class);
    private final HeronNewsProperties news = new HeronNewsProperties();
    private final LocalDate date = LocalDate.of(2026, 11, 24);
    private TrendLearnService service;

    @BeforeEach
    void setUp() {
        news.setTrendLearnOutputDir(tempDir.resolve("learned"));
        news.setTrendLearnBatchSize(10);
        news.setTrendLearnMaxBytes(1234L);
        service = new TrendLearnService(videos, model, downloader, storage, decomposer, agent, documents, news);
        when(documents.today()).thenReturn(date);
        when(documents.writeSummary(eq(date), anySet())).thenReturn(tempDir.resolve("learned").resolve(date.toString()).resolve("techniques.md"));
    }

    @Test
    void oneFailureDoesNotBlockOtherSelectedVideos() throws Exception {
        HeronTrendVideo first = video(1L, "LEARN_FAILED", 100L);
        HeronTrendVideo second = video(2L, "LEARN_FAILED", 101L);
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(first, second));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn("""
            {"selected":[
              {"awemeId":"1","relevance":0.9,"reason":"排行"},
              {"awemeId":"2","relevance":0.8,"reason":"趋势"}
            ]}
            """);
        when(videos.markSelected(anyLong(), any(), any(), anyString())).thenReturn(1);
        when(storage.downloadRaw(any(), any())).thenReturn(tempDir.resolve("restored"));
        when(storage.rawKeys(any())).thenReturn(Map.of("breakdown.json", "raw/breakdown.json"));
        when(agent.learn(eq(first), any(), any(), eq(2)))
            .thenThrow(new HeronBusinessException(502, "model_failed", "模型超时"));
        when(agent.learn(eq(second), any(), any(), eq(2))).thenReturn(card());
        when(videos.markFailed(anyLong(), any(), any(), anyString(), anyString())).thenReturn(1);
        when(videos.markLearned(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(documents.saveCard(any(), any(), anyDouble(), anyString(), eq(date), anyLong(), anyMap()))
            .thenReturn(tempDir.resolve("2.md"));

        TrendLearnService.Report report = service.run();

        assertEquals(2, report.selected());
        assertEquals(1, report.learned());
        assertEquals(1, report.failed());
        assertEquals("1", report.failures().get(0).awemeId());
        assertEquals("LEARN_FAILED", report.failures().get(0).state());
        verify(agent).learn(eq(first), any(), any(), eq(2));
        verify(agent).learn(eq(second), any(), any(), eq(2));
        verify(videos).markFailed(eq(1L), eq(TrendLearnState.SELECTED), eq(TrendLearnState.LEARN_FAILED),
            contains("attempt=2"), anyString());
        verify(documents).saveRaw(eq(second), any(), eq(date));
        verify(videos).markLearned(eq(2L), eq(TrendLearnState.SELECTED), contains("learned"), anyString());
    }

    @Test
    void freshVideoRunsDownloadDecomposeAndLearnInOrder() throws Exception {
        HeronTrendVideo video = video(3L, "SEEN", null);
        video.setDownloadUrl("https://cdn.example.test/3.mp4");
        Path source = Files.writeString(tempDir.resolve("source.mp4"), "video");
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(video));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn(
            "{\"selected\":[{\"awemeId\":\"3\",\"relevance\":1.0,\"reason\":\"AI 科技\"}]}");
        when(videos.markSelected(anyLong(), any(), any(), anyString())).thenReturn(1);
        when(downloader.download(video.getDownloadUrl(), 1234L))
            .thenReturn(new ReferenceVideoDownloader.Downloaded(source, 5L, "a".repeat(64), "video/mp4"));
        when(storage.uploadVideo(eq(video), any())).thenReturn(new TrendLearnStorage.Stored(
            "raw.mp4", "https://oss.example/raw.mp4", 5L, "video/mp4", 300L));
        when(videos.backfillOssId(eq(3L), eq(300L), anyString())).thenReturn(1);
        when(videos.markDownloaded(anyLong(), anyLong(), anyString(), anyString())).thenReturn(1);
        when(decomposer.decompose(any(), any())).thenAnswer(invocation -> {
            Path work = invocation.getArgument(1);
            Path out = work.resolve("out");
            Files.createDirectories(out);
            for (String name : TrendLearnStorage.RAW_FILES) {
                Files.writeString(out.resolve(name), name);
            }
            return new TrendVideoDecomposer.Decomposition(work, out);
        });
        when(storage.uploadRaw(eq(video), any())).thenReturn(Map.of(
            "breakdown.json", new TrendLearnStorage.Stored("breakdown.json", "url", 13L, "application/json", null)));
        when(videos.markDecomposed(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(agent.learn(eq(video), any(), any(), eq(1))).thenReturn(card());
        when(documents.saveCard(any(), any(), anyDouble(), anyString(), eq(date), eq(300L), anyMap()))
            .thenReturn(tempDir.resolve("3.md"));
        when(videos.markLearned(anyLong(), any(), anyString(), anyString())).thenReturn(1);

        TrendLearnService.Report report = service.run();

        assertEquals(1, report.learned());
        InOrder order = inOrder(videos, downloader, storage, decomposer, agent);
        order.verify(videos).markSelected(eq(3L), eq(TrendLearnState.SEEN), isNull(), anyString());
        order.verify(downloader).download(video.getDownloadUrl(), 1234L);
        order.verify(storage).uploadVideo(eq(video), any());
        order.verify(videos).backfillOssId(eq(3L), eq(300L), anyString());
        order.verify(videos).markDownloaded(eq(3L), eq(300L), anyString(), anyString());
        order.verify(decomposer).decompose(any(), any());
        order.verify(videos).markDecomposed(eq(3L), eq(TrendLearnState.DOWNLOADED), anyString(), anyString());
        order.verify(agent).learn(eq(video), any(), any(), eq(1));
        order.verify(videos).markLearned(eq(3L), eq(TrendLearnState.DECOMPOSED), anyString(), anyString());
    }

    @Test
    void watermarkedUrlFailsBeforeNetworkAndDoesNotBlockBatch() {
        HeronTrendVideo video = video(4L, "SEEN", null);
        video.setDownloadUrl("https://cdn.example.test/4.mp4?watermark=1&token=x");
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(video));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn(
            "{\"selected\":[{\"awemeId\":\"4\",\"relevance\":1.0,\"reason\":\"知识解释\"}]}");
        when(videos.markSelected(anyLong(), any(), any(), anyString())).thenReturn(1);
        when(videos.markFailed(anyLong(), any(), any(), anyString(), anyString())).thenReturn(1);

        TrendLearnService.Report report = service.run();

        assertEquals(1, report.failed());
        assertEquals("DOWNLOAD_FAILED", report.failures().get(0).state());
        verify(downloader, never()).download(anyString(), anyLong());
        verify(videos).markFailed(eq(4L), eq(TrendLearnState.SELECTED), eq(TrendLearnState.DOWNLOAD_FAILED),
            contains("watermark"), anyString());
    }

    @Test
    void concurrentClaimConflictSkipsVideoWithoutProcessing() {
        HeronTrendVideo video = video(6L, "SEEN", null);
        video.setDownloadUrl("https://cdn.example.test/6.mp4");
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(video));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn(
            "{\"selected\":[{\"awemeId\":\"6\",\"relevance\":1.0,\"reason\":\"知识解释\"}]}");
        when(videos.markSelected(anyLong(), any(), any(), anyString())).thenReturn(0);

        TrendLearnService.Report report = service.run();

        assertEquals(1, report.skipped());
        assertEquals(0, report.learned());
        verify(agent, never()).learn(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(downloader, never()).download(anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nextDayRetryRestoresRawAndWritesCurrentDayOutputs() throws Exception {
        LocalDate nextDate = LocalDate.of(2026, 11, 25);
        news.setTrendLearnOutputDir(tempDir.resolve("next-learn"));
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));
        TrendLearnDocuments realDocuments = new TrendLearnDocuments(news, clocks);
        TrendLearnService realService = new TrendLearnService(videos, model, downloader, storage, decomposer,
            agent, realDocuments, news);
        HeronTrendVideo video = video(7L, "LEARN_FAILED", 700L);
        Path ossOutput = tempDir.resolve("oss-output");
        Files.createDirectories(ossOutput);
        for (String name : TrendLearnStorage.RAW_FILES) {
            Files.writeString(ossOutput.resolve(name), name.equals("storyboard.jpg") ? "jpeg" : name);
        }
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(video));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn(
            "{\"selected\":[{\"awemeId\":\"7\",\"relevance\":0.9,\"reason\":\"趋势\"}]}");
        when(videos.markSelected(anyLong(), any(), any(), anyString())).thenReturn(1);
        when(storage.downloadRaw(eq(video), any())).thenReturn(ossOutput);
        when(storage.rawKeys(video)).thenReturn(Map.of("breakdown.json", "raw/7/breakdown.json"));
        when(agent.learn(eq(video), any(), any(), eq(2))).thenReturn(card());
        when(videos.markLearned(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(videos.selectLearnedAwemeIds()).thenReturn(List.of("7"));

        TrendLearnService.Report report = realService.run();

        assertEquals(1, report.learned());
        Path day = tempDir.resolve("next-learn").resolve(nextDate.toString());
        assertTrue(Files.isRegularFile(day.resolve("7.md")));
        assertTrue(Files.isRegularFile(day.resolve("7.jpg")));
        assertTrue(Files.isRegularFile(day.resolve("_raw/7/breakdown.json")));
        assertTrue(Files.readString(day.resolve("techniques.md")).contains("7"));
    }

    @Test
    void staleSelectedCannotBeClaimedByTwoWorkers() throws Exception {
        HeronTrendVideo video = video(8L, "SELECTED", 800L);
        String oldNote = "attempt=1; token=old; resume=learn; lease_until=0";
        video.setLearnNote(oldNote);
        AtomicReference<String> currentNote = new AtomicReference<>(oldNote);
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(video));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn(
            "{\"selected\":[{\"awemeId\":\"8\",\"relevance\":0.9,\"reason\":\"趋势\"}]}");
        when(videos.markSelected(anyLong(), any(), any(), anyString())).thenAnswer(invocation ->
            currentNote.compareAndSet(invocation.getArgument(2), invocation.getArgument(3)) ? 1 : 0);
        when(storage.downloadRaw(eq(video), any())).thenReturn(tempDir.resolve("restored-8"));
        when(storage.rawKeys(video)).thenReturn(Map.of());
        when(agent.learn(eq(video), any(), any(), eq(2))).thenReturn(card());
        when(documents.saveCard(any(), any(), anyDouble(), anyString(), eq(date), eq(800L), anyMap()))
            .thenReturn(tempDir.resolve("8.md"));
        when(videos.markLearned(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(videos.selectLearnedAwemeIds()).thenReturn(List.of("8"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<TrendLearnService.Report> worker = () -> {
            ready.countDown();
            start.await();
            return service.run();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TrendLearnService.Report> first = executor.submit(worker);
            Future<TrendLearnService.Report> second = executor.submit(worker);
            ready.await();
            start.countDown();
            int skipped = first.get().skipped() + second.get().skipped();
            int learned = first.get().learned() + second.get().learned();
            assertEquals(1, skipped);
            assertEquals(1, learned);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidSelectionStopsBeforeClaimingAnyVideo() {
        HeronTrendVideo video = video(5L, "SEEN", null);
        video.setDownloadUrl("https://cdn.example.test/5.mp4");
        when(videos.selectLearnCandidates(90_000L)).thenReturn(List.of(video));
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn("我觉得选 5");

        assertThrows(HeronBusinessException.class, service::run);

        verify(videos, never()).markSelected(anyLong(), any(), any(), anyString());
    }

    private static HeronTrendVideo video(long id, String state, Long ossId) {
        HeronTrendVideo video = new HeronTrendVideo();
        video.setId(id);
        video.setPlatform("douyin");
        video.setAwemeId(String.valueOf(id));
        video.setDescription("测试视频");
        video.setAuthorNickname("作者");
        video.setDurationMs(20_000L);
        video.setDiggCount(10L);
        video.setLearnState(state);
        video.setLearnNote("SEEN".equals(state) ? null : "attempt=1; previous failure");
        video.setOssId(ossId);
        return video;
    }

    private static TrendTechniqueCard card() {
        return new TrendTechniqueCard("反常识提问", 0.5, 2.0, "前快后慢", "大字号短句",
            List.of("数字滚动"), "中", List.of("先抛结论"), "news-rank-9x16：适合排行反差");
    }
}
