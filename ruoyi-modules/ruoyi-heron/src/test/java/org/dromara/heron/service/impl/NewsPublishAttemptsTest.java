package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsPublishAttempt;
import org.dromara.heron.mapper.HeronNewsPublishAttemptMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsPublishAttemptsTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-18T08:10:00");

    private static HeronNewsPublishAttempt attempt(String status) {
        var row = new HeronNewsPublishAttempt();
        row.setId(1L);
        row.setCandidateId(2L);
        row.setWorkId(3L);
        row.setVersionId(4L);
        row.setVideoSha("a".repeat(64));
        row.setSource("douyin-cli");
        row.setStatus(status);
        row.setStartedAt(NOW);
        return row;
    }

    @Test
    void anyActiveCandidateAttemptBlocksAnotherVersionBeforeInsert() {
        var mapper = mock(HeronNewsPublishAttemptMapper.class);
        when(mapper.lockActive(2)).thenReturn(attempt("unknown"));
        var service = new NewsPublishAttempts(mapper);
        assertThrows(HeronBusinessException.class, () -> service.begin(2, 3, 99, "b".repeat(64), "douyin-cli", "heron", 101, NOW));
        verify(mapper, never()).insert(any(HeronNewsPublishAttempt.class));
    }

    @Test
    void newAttemptBindsCandidateVersionAndVideoBeforeCallingExternalCode() {
        var mapper = mock(HeronNewsPublishAttemptMapper.class);
        when(mapper.insert(any(HeronNewsPublishAttempt.class))).thenReturn(1);
        var row = new NewsPublishAttempts(mapper).begin(2, 3, 4, "a".repeat(64), "douyin-cli", "heron", 101, NOW);
        assertEquals("started", row.getStatus());
        assertEquals(2L, row.getCandidateId());
        assertEquals(4L, row.getVersionId());
        assertEquals("a".repeat(64), row.getVideoSha());
        assertEquals("heron", row.getAccount());
        assertNotNull(row.getId());
        assertEquals(NOW, row.getStartedAt());
    }

    @Test
    void uncertainOutcomeNeverRollsBackACompletedOrHumanResolvedState() {
        for (String state : new String[] {"submitted", "published", "not_published"}) {
            var mapper = mock(HeronNewsPublishAttemptMapper.class);
            var persisted = new AtomicReference<>(state);
            doAnswer(call -> {
                if (!persisted.get().equals(call.getArgument(1))) return 0;
                persisted.set(call.getArgument(2));
                return 1;
            }).when(mapper).transition(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
            var service = new NewsPublishAttempts(mapper);
            service.unknown(1, "response_lost", NOW);
            assertEquals(state, persisted.get());
            assertThrows(HeronBusinessException.class, () -> service.finish(attempt("started"), "submitted", "heron", NOW));
            assertEquals(state, persisted.get(), "迟到的上传结果不能覆盖人工结论");
        }
    }

    @Test
    void negativeResolutionRequiresStoppedUploadAndWaitForAnActiveCommand() {
        var row = attempt("started");
        assertThrows(HeronBusinessException.class, () -> NewsPublishAttempts.requireResolvable(
            row, "not_published", "已核对平台", false, 300, NOW.plusMinutes(30)));
        assertThrows(HeronBusinessException.class, () -> NewsPublishAttempts.requireResolvable(
            row, "not_published", "已核对平台", true, 300, NOW.plusSeconds(419)));
        assertDoesNotThrow(() -> NewsPublishAttempts.requireResolvable(
            row, "not_published", "已停止上传并确认未发出", true, 300, NOW.plusSeconds(420)));
        assertThrows(HeronBusinessException.class, () -> NewsPublishAttempts.requireResolvable(
            row, null, "核对", true, 300, NOW));
    }

    @Test
    void settledFactsCannotBeReleasedAndResolutionMustHaveAReason() {
        assertThrows(HeronBusinessException.class, () -> NewsPublishAttempts.requireResolvable(
            attempt("published"), "not_published", "核对", true, 300, NOW.plusDays(1)));
        assertThrows(HeronBusinessException.class, () -> NewsPublishAttempts.requireResolvable(
            attempt("unknown"), "not_published", " ", true, 300, NOW));
        assertDoesNotThrow(() -> NewsPublishAttempts.requireResolvable(
            attempt("unknown"), "published", "核对平台作品链接与实际时间", false, 300, NOW));
    }
}
