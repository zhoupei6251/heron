package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendLearnState;
import org.dromara.heron.service.impl.shared.research.TrendLearnService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendLearnServiceTest {

    @Test
    void hardFilterKeepsOnlyRetryableShortVideosWithInteractions() {
        HeronTrendVideo seen = video(1L, "SEEN", 90_000L, 5L);
        seen.setDownloadUrl("https://cdn.example.test/1.mp4");
        assertTrue(TrendLearnService.eligible(seen, 90_000L));

        HeronTrendVideo learned = video(2L, "LEARNED", 10_000L, 5L);
        learned.setDownloadUrl("https://cdn.example.test/2.mp4");
        assertFalse(TrendLearnService.eligible(learned, 90_000L));

        HeronTrendVideo selected = video(3L, "SELECTED", 10_000L, 5L);
        selected.setDownloadUrl("https://cdn.example.test/3.mp4");
        selected.setLearnNote("attempt=1; resume=download; lease_until="
            + Instant.now().plusSeconds(600).toEpochMilli());
        assertFalse(TrendLearnService.eligible(selected, 90_000L));

        HeronTrendVideo quiet = video(4L, "SEEN", 10_000L, 0L);
        quiet.setDownloadUrl("https://cdn.example.test/4.mp4");
        assertFalse(TrendLearnService.eligible(quiet, 90_000L));

        HeronTrendVideo unknownDuration = video(5L, "SEEN", null, 5L);
        unknownDuration.setDownloadUrl("https://cdn.example.test/5.mp4");
        assertFalse(TrendLearnService.eligible(unknownDuration, 90_000L));

        HeronTrendVideo tooLong = video(6L, "SEEN", 90_001L, 5L);
        tooLong.setDownloadUrl("https://cdn.example.test/6.mp4");
        assertFalse(TrendLearnService.eligible(tooLong, 90_000L));
    }

    @Test
    void failedStagesRetryFromTheirStoredOssArtifacts() {
        HeronTrendVideo decomposeFailed = video(7L, "DECOMPOSE_FAILED", 20_000L, 3L);
        decomposeFailed.setOssId(100L);
        assertTrue(TrendLearnService.eligible(decomposeFailed, 90_000L));

        HeronTrendVideo learnFailed = video(8L, "LEARN_FAILED", 20_000L, 3L);
        learnFailed.setOssId(101L);
        assertTrue(TrendLearnService.eligible(learnFailed, 90_000L));

        HeronTrendVideo missingOss = video(9L, "LEARN_FAILED", 20_000L, 3L);
        assertFalse(TrendLearnService.eligible(missingOss, 90_000L));
    }

    @Test
    void staleIntermediateStatesRecoverButActiveLeasesDoNot() {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        HeronTrendVideo selected = video(10L, "SELECTED", 20_000L, 3L);
        selected.setDownloadUrl("https://cdn.example.test/10.mp4");
        selected.setLearnNote("attempt=1; resume=download; lease_until=" + now.plusSeconds(60).toEpochMilli());
        assertFalse(TrendLearnService.eligible(selected, 90_000L, now));
        selected.setLearnNote("attempt=1; resume=download; lease_until=" + now.minusSeconds(1).toEpochMilli());
        assertTrue(TrendLearnService.eligible(selected, 90_000L, now));

        selected.setOssId(100L);
        assertEquals("decompose", TrendLearnService.resume(TrendLearnState.SELECTED, 100L, selected.getLearnNote()));

        HeronTrendVideo downloaded = video(11L, "DOWNLOADED", 20_000L, 3L);
        downloaded.setOssId(101L);
        downloaded.setLearnNote("attempt=1; lease_until=" + now.minusSeconds(1).toEpochMilli());
        assertTrue(TrendLearnService.eligible(downloaded, 90_000L, now));
        assertEquals("decompose", TrendLearnService.resume(TrendLearnState.DOWNLOADED, 101L, downloaded.getLearnNote()));

        HeronTrendVideo decomposed = video(12L, "DECOMPOSED", 20_000L, 3L);
        decomposed.setOssId(102L);
        decomposed.setLearnNote("attempt=1; lease_until=" + now.minusSeconds(1).toEpochMilli());
        assertTrue(TrendLearnService.eligible(decomposed, 90_000L, now));
        assertEquals("learn", TrendLearnService.resume(TrendLearnState.DECOMPOSED, 102L, decomposed.getLearnNote()));
    }

    @Test
    void selectionParserSortsDeduplicatesAndLimitsRealCandidates() {
        HeronTrendVideo first = video(11L, "SEEN", 10_000L, 3L);
        first.setAwemeId("11");
        HeronTrendVideo second = video(12L, "SEEN", 10_000L, 3L);
        second.setAwemeId("12");
        String reply = """
            {"selected":[
              {"awemeId":"11","relevance":0.5,"reason":"一般相关"},
              {"awemeId":"12","relevance":0.9,"reason":"高度相关"},
              {"awemeId":"12","relevance":0.8,"reason":"重复项"}
            ]}
            """;

        List<TrendLearnService.RankedVideo> ranked = TrendLearnService.parseSelection(reply, List.of(first, second), 1);

        assertEquals(1, ranked.size());
        assertEquals("12", ranked.get(0).video().getAwemeId());
        assertEquals(0.9, ranked.get(0).relevance());
    }

    @Test
    void selectionParserAllowsZeroButRejectsUnknownIds() {
        HeronTrendVideo first = video(11L, "SEEN", 10_000L, 3L);
        first.setAwemeId("11");
        assertTrue(TrendLearnService.parseSelection("{\"selected\":[]}", List.of(first), 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TrendLearnService.parseSelection(
            "{\"selected\":[{\"awemeId\":\"99\",\"relevance\":0.9,\"reason\":\"编造\"}]}", List.of(first), 10));
    }

    @Test
    void attemptCountIsReadableAndBounded() {
        assertEquals(0, TrendLearnService.attemptOf(null));
        assertEquals(2, TrendLearnService.attemptOf("attempt=2; download_failed: 403"));
        String note = TrendLearnService.note(4, "download_failed: " + "x".repeat(700));
        assertTrue(note.startsWith("attempt=4; "));
        assertTrue(note.length() <= 500);
    }

    private static HeronTrendVideo video(long id, String state, Long durationMs, long digg) {
        HeronTrendVideo video = new HeronTrendVideo();
        video.setId(id);
        video.setPlatform("douyin");
        video.setAwemeId(String.valueOf(id));
        video.setLearnState(state);
        video.setDurationMs(durationMs);
        video.setDiggCount(digg);
        return video;
    }
}
