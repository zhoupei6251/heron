package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendTechniqueCard;
import org.dromara.heron.service.impl.shared.research.TrendLearnDocuments;
import org.dromara.heron.service.impl.shared.research.TrendLearnStorage;
import org.dromara.heron.service.impl.shared.research.TrendVideoDecomposer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrendLearnDocumentsTest {

    @TempDir
    Path tempDir;

    private final LocalDate date = LocalDate.of(2026, 11, 24);
    private TrendLearnDocuments documents;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        HeronNewsProperties news = new HeronNewsProperties();
        news.setTrendLearnOutputDir(tempDir.resolve("learned"));
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC));
        documents = new TrendLearnDocuments(news, clocks);
    }

    @Test
    void writesRawStoryboardCardsAndCrossVideoSummary() throws Exception {
        Path work = tempDir.resolve("work");
        Path out = work.resolve("out");
        Files.createDirectories(out);
        for (String name : TrendLearnStorage.RAW_FILES) {
            Files.writeString(out.resolve(name), name.equals("storyboard.jpg") ? "jpeg-bytes" : name);
        }
        TrendVideoDecomposer.Decomposition decomposition = new TrendVideoDecomposer.Decomposition(work, out);
        HeronTrendVideo first = video("101");
        HeronTrendVideo second = video("202");
        documents.saveRaw(first, decomposition, date);
        documents.saveRaw(second, decomposition, date);
        documents.saveCard(first, card("反常识提问", "news-rank-9x16：适合排行"), 0.9, "排行", date,
            1001L, Map.of("breakdown.json", "raw/101/breakdown.json"));
        documents.saveCard(second, card("悬念前置", "news-trend-9x16：适合趋势"), 0.8, "趋势", date,
            1002L, Map.of("breakdown.json", "raw/202/breakdown.json"));

        Path summary = documents.writeSummary(date, Set.of("101", "202"));
        String text = Files.readString(summary);

        assertEquals(date, documents.today());
        assertTrue(Files.isRegularFile(documents.cardPath(first, date)));
        assertTrue(Files.isRegularFile(tempDir.resolve("learned").resolve(date.toString()).resolve("101.jpg")));
        assertTrue(Files.isRegularFile(tempDir.resolve("learned").resolve(date.toString()).resolve("_raw/101/breakdown.json")));
        assertTrue(text.contains("成功学习 2 条"));
        assertTrue(text.contains("101"));
        assertTrue(text.contains("202"));
        assertTrue(text.contains("可复用：先抛结论"));
        assertTrue(text.contains("`cutRhythm`：前快后慢"));
        assertTrue(text.contains("news-rank-9x16"));
        assertTrue(text.contains("news-trend-9x16"));
    }

    @Test
    void zeroSelectionStillWritesHonestSummary() throws Exception {
        Path summary = documents.writeSummary(date, Set.of());
        assertTrue(Files.readString(summary).contains("本日暂无成功学习样本"));
    }

    @Test
    void summaryExcludesCardWhoseDatabaseStateNeverReachedLearned() throws Exception {
        documents.saveCard(video("303"), card("反常识提问", "news-rank-9x16：适合排行"), 0.9, "排行", date,
            1003L, Map.of());

        Path summary = documents.writeSummary(date, Set.of());

        assertTrue(Files.readString(summary).contains("本日暂无成功学习样本"));
    }

    private static HeronTrendVideo video(String awemeId) {
        HeronTrendVideo video = new HeronTrendVideo();
        video.setId(Long.parseLong(awemeId));
        video.setPlatform("douyin");
        video.setAwemeId(awemeId);
        video.setDescription("测试视频");
        video.setAuthorNickname("作者");
        video.setDurationMs(20_000L);
        return video;
    }

    private static TrendTechniqueCard card(String hook, String fit) {
        return new TrendTechniqueCard(hook, 0.5, 2.0, "前快后慢", "大字号短句",
            List.of("数字滚动"), "中", List.of("先抛结论", "证据分层"), fit);
    }
}
