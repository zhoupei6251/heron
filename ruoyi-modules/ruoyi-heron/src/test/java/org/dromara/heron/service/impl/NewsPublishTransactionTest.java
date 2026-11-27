package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.domain.bo.NewsReviewBo;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.service.IPublishAdapter;
import org.dromara.heron.service.impl.shared.production.NewsWorkServiceImpl;
import org.dromara.heron.service.impl.shared.publish.NewsPublishAttempts;
import org.dromara.heron.service.impl.shared.support.HeronAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实TransactionTemplate边界；数据库锁/唯一性由独立mysql整合用例验证。 */
class NewsPublishTransactionTest {
    private static class Transactions extends AbstractPlatformTransactionManager {
        int commits;
        int failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (++commits == failCommit) throw new TransactionSystemException("commit failed");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    private static class Fixture {
        final Transactions tx = new Transactions();
        final IPublishAdapter adapter = mock(IPublishAdapter.class);
        final NewsPublishAttempts attempts = mock(NewsPublishAttempts.class);
        final HeronAccessService access = mock(HeronAccessService.class);
        final NewsVideoVersionVo view = new NewsVideoVersionVo();
        boolean replay;
        boolean failReceipt;
        final NewsWorkServiceImpl service;

        Fixture() {
            view.setId(4L);
            when(access.requireActor()).thenReturn(101L);
            when(attempts.latest(anyList())).thenReturn(Map.of());
            var clock = Clock.fixed(Instant.parse("2026-11-18T08:10:00Z"), ZoneOffset.UTC);
            var clocks = new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(Clock.class);
            service = new NewsWorkServiceImpl(null, null, null, null, null, access, null, null, null, null,
                tx, clocks, adapter, new HeronNewsProperties(), attempts, null, null) {
                @Override public PreparedPublish prepareConfirm(long work, long version, NewsReviewBo review, long actor, boolean system) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    PublishRequest request = replay ? null : new PublishRequest(8, version, "标题", "a".repeat(64),
                        LocalDateTime.now(clock), Path.of("fixture.mp4"));
                    return new PreparedPublish(view, request, 9L);
                }
                @Override public NewsVideoVersionVo finishConfirm(long work, long version, long attemptId,
                                                            PublishReceipt receipt, long actor, boolean system) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    if (failReceipt) throw new IllegalStateException("receipt write failed");
                    return view;
                }
            };
            when(adapter.publish(any())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "上传必须在数据库事务外");
                assertEquals(1, tx.commits, "上传前的尝试必须已提交");
                return new PublishReceipt("sandbox://news/4", "sandbox", LocalDateTime.now(clock), "sandbox");
            });
        }
    }

    @Test
    void bothManualAndAutomaticConfirmationCommitIntentBeforeUploading() {
        for (boolean auto : new boolean[] {false, true}) {
            var f = new Fixture();
            if (auto) f.service.confirmForAuto(3, 4, 101);
            else f.service.confirm(3, 4, null);
            assertEquals(2, f.tx.commits);
            verify(f.adapter, times(1)).publish(any());
        }
    }

    @Test
    void failedIntentCommitPreventsAnyUpload() {
        var f = new Fixture();
        f.tx.failCommit = 1;
        assertThrows(TransactionSystemException.class, () -> f.service.confirm(3, 4, null));
        verifyNoInteractions(f.adapter);
    }

    @Test
    void failedUploadOrReceiptCommitPersistsUnknownWithoutRetry() {
        for (int failure : new int[] {1, 2, 3}) {
            var f = new Fixture();
            if (failure == 1) doThrow(new HeronBusinessException(504, "publish_timeout", "timeout")).when(f.adapter).publish(any());
            if (failure == 2) f.failReceipt = true;
            if (failure == 3) f.tx.failCommit = 2;
            assertEquals("news_publish_unknown", assertThrows(HeronBusinessException.class,
                () -> f.service.confirm(3, 4, null)).getCode());
            verify(f.attempts).unknown(eq(9L), anyString(), any());
            verify(f.adapter, times(1)).publish(any());
        }
    }

    @Test
    void existingAttemptIsReplayedWithoutASecondUpload() {
        var f = new Fixture();
        f.replay = true;
        assertSame(f.view, f.service.confirm(3, 4, null));
        verifyNoInteractions(f.adapter);
        assertEquals(1, f.tx.commits);
    }
}
