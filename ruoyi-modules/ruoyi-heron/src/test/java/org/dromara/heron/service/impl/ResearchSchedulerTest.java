package org.dromara.heron.service.impl;

import org.dromara.heron.service.IResearchTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 调度器一拍的两件事：先收尾失联的取消，再把可领取的任务递出去。
 *
 * <p>顺序有意义：先收尾再领取，否则一拍里可能把一条本该收尾的 cancelling 拖到下一拍。
 */
@DisplayName("研究调度：取消收尾与领取")
class ResearchSchedulerTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<IResearchTaskService> provider(IResearchTaskService service) {
        ObjectProvider<IResearchTaskService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @Test
    @DisplayName("先收尾失联取消，再逐条触发可领取的任务")
    void tickResolvesThenTriggers() {
        IResearchTaskService service = mock(IResearchTaskService.class);
        when(service.resolveAbandonedCancels()).thenReturn(1);
        when(service.claimableIds(anyInt())).thenReturn(List.of(7L, 8L));

        new ResearchScheduler(provider(service), mock(ScheduledExecutorService.class)).tick();

        var order = inOrder(service);
        order.verify(service).resolveAbandonedCancels();
        order.verify(service).claimableIds(ResearchScheduler.BATCH);
        order.verify(service).trigger(7L);
        order.verify(service).trigger(8L);
    }

    @Test
    @DisplayName("服务没接上时安静返回")
    @SuppressWarnings("unchecked")
    void tickWithoutServiceDoesNothing() {
        ObjectProvider<IResearchTaskService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() -> new ResearchScheduler(provider, mock(ScheduledExecutorService.class)).tick());
    }

    /** 一条失败不该把这一拍剩下的任务一起丢掉。 */
    @Test
    @DisplayName("领取抛错时不影响取消收尾先完成")
    void tickToleratesClaimFailure() {
        IResearchTaskService service = mock(IResearchTaskService.class);
        when(service.claimableIds(anyInt())).thenThrow(new IllegalStateException("db down"));

        new ResearchScheduler(provider(service), mock(ScheduledExecutorService.class)).tick();

        verify(service).resolveAbandonedCancels();
    }
}
