package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.vo.ResearchTaskVo;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.IResearchAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 建单幂等 + 领取条件 + 终态映射。
 *
 * <p>涉及 wrapper 的用例用真实 {@code LambdaCrudChainWrapper}：断言的是实际生成的更新条件，
 * mock 掉 wrapper 等于什么都不验。
 */
@DisplayName("研究任务：建单、领取与终态")
class ResearchTaskServiceImplTest {

    /** 真实 wrapper 要解析 lambda 到列名，单测里得先有表元数据。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "researchTask"), HeronResearchTask.class);
    }

    /** 决策服务在测试里通常不存在：用空 provider 模拟「没接上」。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.dromara.heron.service.IContentDecisionService> noDecisions() {
        ObjectProvider<org.dromara.heron.service.IContentDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ResearchTaskServiceImpl service(HeronResearchTaskMapper mapper) {
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);
        return new ResearchTaskServiceImpl(mapper, mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class),
            mock(IResearchAgent.class), clocks, noDecisions());
    }

    /** 已领取的一次执行：每次取 wrapper 都是新的，按顺序收进 chains。 */
    private record Harness(HeronResearchTaskMapper mapper,
                           List<LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>> chains) {
    }

    @SuppressWarnings("unchecked")
    private static Harness claimed() {
        HeronResearchTask queued = new HeronResearchTask();
        queued.setResearchId(9L);
        queued.setStatus(HeronResearchTask.STATUS_QUEUED);
        queued.setAttemptNo(0);
        queued.setSessionId("research-9");
        queued.setGoal("机会 9 在候选里。");

        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(9L)).thenReturn(queued);
        // 领取那一步走 BaseMapperPlus 的单参 update(wrapper)，返回 1 才算领到
        when(mapper.update(any(Wrapper.class))).thenReturn(1);

        List<LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>> chains = new ArrayList<>();
        when(mapper.lambda()).thenAnswer(invocation -> {
            var chain = new LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>(mapper);
            chains.add(chain);
            return chain;
        });
        return new Harness(mapper, chains);
    }

    @SuppressWarnings("unchecked")
    private static String runWith(Harness harness, IResearchAgent.Outcome outcome) {
        IResearchAgent agent = mock(IResearchAgent.class);
        when(agent.research(any())).thenReturn(outcome);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);
        return new ResearchTaskServiceImpl(harness.mapper(),
            mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class), agent, clocks, noDecisions()).run(9L);
    }

    @Test
    @DisplayName("建单按机会评分定档，没评分就最低档")
    void gradesFromOpportunityScore() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        org.dromara.heron.mapper.HeronDataOpportunityMapper opportunities =
            mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class);
        var row = new org.dromara.heron.domain.HeronDataOpportunity();
        row.setOpportunityId(7L);
        row.setScore(new java.math.BigDecimal("82.5"));
        when(opportunities.selectById(7L)).thenReturn(row);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        new ResearchTaskServiceImpl(mapper, opportunities, mock(IResearchAgent.class), clocks, noDecisions())
            .createFor(7L, "目标");

        var captor = org.mockito.ArgumentCaptor.forClass(HeronResearchTask.class);
        verify(mapper).insert(captor.capture());
        assertEquals("L3", captor.getValue().getGrade(), "高评分该走最高档");
    }

    /**
     * 查重必须发生在建单之前：等到跑完再提示重复，token 已经烧掉了。
     */
    @Test
    @DisplayName("已有待处理决策的机会复用原研究，不新建")
    void reusesResearchWithExistingDecision() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        var decision = new org.dromara.heron.domain.HeronContentDecision();
        decision.setResearchId(314L);
        var oldTask = new HeronResearchTask();
        oldTask.setResearchId(314L);
        when(mapper.selectById(314L)).thenReturn(oldTask);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.dromara.heron.service.IContentDecisionService> decisions = mock(ObjectProvider.class);
        var service = mock(org.dromara.heron.service.IContentDecisionService.class);
        when(service.findExistingDecision(7L)).thenReturn(decision);
        when(decisions.getIfAvailable()).thenReturn(service);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        long id = new ResearchTaskServiceImpl(mapper,
            mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class),
            mock(IResearchAgent.class), clocks, decisions).createFor(7L, "目标");

        assertEquals(314L, id);
        verify(mapper, never()).insert(any(HeronResearchTask.class));
    }

    /**
     * 决策还在、引用的研究已经被清理：这时候复用等于返回一个不存在的任务，
     * 调用方拿到的 data 会是 null。应当按新任务处理。
     */
    @Test
    @DisplayName("决策引用的研究已不存在时按新任务处理，不返回悬空 id")
    void ignoresDecisionWhoseResearchIsGone() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectById(314L)).thenReturn(null);
        var decision = new org.dromara.heron.domain.HeronContentDecision();
        decision.setResearchId(314L);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.dromara.heron.service.IContentDecisionService> decisions = mock(ObjectProvider.class);
        var service = mock(org.dromara.heron.service.IContentDecisionService.class);
        when(service.findExistingDecision(7L)).thenReturn(decision);
        when(decisions.getIfAvailable()).thenReturn(service);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        long id = new ResearchTaskServiceImpl(mapper,
            mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class),
            mock(IResearchAgent.class), clocks, decisions).createFor(7L, "目标");

        assertNotEquals(314L, id);
        verify(mapper).insert(any(HeronResearchTask.class));
    }

    @Test
    @DisplayName("查重失败不挡建单：宁可多花一次钱，也别让用户点不动")
    void dedupFailureDoesNotBlock() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.dromara.heron.service.IContentDecisionService> decisions = mock(ObjectProvider.class);
        var service = mock(org.dromara.heron.service.IContentDecisionService.class);
        when(service.findExistingDecision(7L)).thenThrow(new IllegalStateException("db down"));
        when(decisions.getIfAvailable()).thenReturn(service);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() -> new ResearchTaskServiceImpl(mapper,
            mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class),
            mock(IResearchAgent.class), clocks, decisions).createFor(7L, "目标"));
        verify(mapper).insert(any(HeronResearchTask.class));
    }

    @Test
    @DisplayName("去重键同日稳定，跨日不同")
    void dedupKeyScope() {
        LocalDate day = LocalDate.of(2026, 11, 13);
        String base = ResearchTaskServiceImpl.dedupKey(2121151796750819328L, day);

        assertEquals(base, ResearchTaskServiceImpl.dedupKey(2121151796750819328L, day));
        assertEquals(64, base.length(), "sha256 hex");
        assertNotEquals(base, ResearchTaskServiceImpl.dedupKey(2121151796750819328L, day.plusDays(1)));
        assertNotEquals(base, ResearchTaskServiceImpl.dedupKey(999L, day), "换机会是另一个任务");
    }

    @Test
    @DisplayName("同机会同日已有任务时直接返回它，不再插新行")
    void returnsExistingInsteadOfInserting() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        HeronResearchTask existing = new HeronResearchTask();
        existing.setResearchId(42L);
        when(mapper.selectOne(any())).thenReturn(existing);

        long id = service(mapper).createFor(2121151796750819328L, "判断这条值不值得做");

        assertEquals(42L, id);
        verify(mapper, never()).insert(any(HeronResearchTask.class));
    }

    @Test
    @DisplayName("建新单时会话标识跟着任务走，重试才能续上同一段上下文")
    void newTaskPinsSessionId() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);

        service(mapper).createFor(7L, "目标");

        var captor = org.mockito.ArgumentCaptor.forClass(HeronResearchTask.class);
        verify(mapper).insert(captor.capture());
        HeronResearchTask inserted = captor.getValue();
        assertEquals("research-" + inserted.getResearchId(), inserted.getSessionId());
        assertEquals(HeronResearchTask.STATUS_QUEUED, inserted.getStatus());
        assertEquals(0, inserted.getAttemptNo());
    }

    @Test
    @DisplayName("领取条件认排队中，也认租约已过期的执行中")
    void claimAcceptsQueuedAndExpiredLease() {
        Harness harness = claimed();

        runWith(harness, new IResearchAgent.Outcome("结论", 1, 0, 0, 0, false, false, null));

        String where = harness.chains().get(0).getCustomSqlSegment();
        assertTrue(where.contains("status"), "要按状态判定：" + where);
        assertTrue(where.contains("lease_until"), "要认租约过期的执行中，否则挂掉的任务永远卡在 running：" + where);
    }

    @Test
    @DisplayName("轮数跑满标 partial，不是 succeeded")
    void maxItersMarksPartial() {
        String status = runWith(claimed(),
            new IResearchAgent.Outcome("半截结论", 8, 2, 100, 50, false, true, null));

        assertEquals(HeronResearchTask.STATUS_PARTIAL, status);
    }

    @Test
    @DisplayName("预算到点同样标 partial")
    void budgetExceededMarksPartial() {
        String status = runWith(claimed(),
            new IResearchAgent.Outcome("半截结论", 3, 2, 100, 50, true, false, null));

        assertEquals(HeronResearchTask.STATUS_PARTIAL, status);
    }

    @Test
    @DisplayName("正常跑完就是 succeeded")
    void cleanRunSucceeds() {
        String status = runWith(claimed(),
            new IResearchAgent.Outcome("结论", 3, 2, 100, 50, false, false, null));

        assertEquals(HeronResearchTask.STATUS_SUCCEEDED, status);
    }

    /**
     * 轮数跑满但模型自己交过结论：那不是被截断，是它判断「证据够了」。
     * 把它标 partial 会让人误以为还得再看一遍。
     */
    @Test
    @DisplayName("轮数跑满但模型主动收尾：算 succeeded，不算 partial")
    void selfStoppedRunIsNotPartial() {
        String status = runWith(claimed(),
            new IResearchAgent.Outcome("完整结论", 8, 6, 100, 50, false, true,
                new IResearchAgent.Findings("REJECT", null, true, null, null)));

        assertEquals(HeronResearchTask.STATUS_SUCCEEDED, status);
    }

    @Test
    @DisplayName("模型给的 judgement 落进 decision_type 与 confidence")
    void writesDecisionFields() {
        Harness harness = claimed();

        runWith(harness, new IResearchAgent.Outcome("结论", 3, 2, 100, 50, false, false,
            new IResearchAgent.Findings("PRODUCE", new java.math.BigDecimal("0.8"), true, null, null)));

        var values = harness.chains().get(harness.chains().size() - 1).getParamNameValuePairs().values();
        assertTrue(values.contains("PRODUCE"), "decision_type 没写进去");
        assertTrue(values.contains(new java.math.BigDecimal("0.8")), "confidence 没写进去");
    }

    /**
     * token 两列以前一直是空的，接了预算之后必须真的写进去，否则「token 预算」看着有、实际查不到。
     */
    @Test
    @DisplayName("跑完一次研究把 token 用量写回任务行")
    void writesTokenUsage() {
        Harness harness = claimed();

        runWith(harness, new IResearchAgent.Outcome("结论", 3, 2, 7927L, 3288L, false, false, null));

        // 最后一次更新是结果写回；领取那次是另一份 wrapper
        var values = harness.chains().get(harness.chains().size() - 1).getParamNameValuePairs().values();
        assertTrue(values.contains(7927L), "input_tokens 没写进去");
        assertTrue(values.contains(3288L), "output_tokens 没写进去");
    }

    /**
     * 短任务可能在续命线程下一拍之前就跑完，此时库里已经是 cancelled。
     * 用户点取消要的是「别用这个结果」，不能被 succeeded 覆盖掉。
     */
    @Test
    @DisplayName("执行期间被取消：跑完也写 cancelled，不写 succeeded")
    void cancellationWinsOverCompletion() {
        HeronResearchTask running = new HeronResearchTask();
        running.setResearchId(9L);
        running.setStatus(HeronResearchTask.STATUS_QUEUED);
        running.setAttemptNo(0);
        running.setSessionId("research-9");
        running.setGoal("机会 9 在候选里。");
        HeronResearchTask cancelledRow = new HeronResearchTask();
        cancelledRow.setResearchId(9L);
        cancelledRow.setStatus(HeronResearchTask.STATUS_CANCELLED);

        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        // 第一次读是领取前，之后读到的都是「已被取消」
        when(mapper.selectById(9L)).thenReturn(running, cancelledRow);
        when(mapper.update(any(Wrapper.class))).thenReturn(1);
        when(mapper.lambda()).thenAnswer(invocation ->
            new LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>(mapper));

        IResearchAgent agent = mock(IResearchAgent.class);
        when(agent.research(any())).thenReturn(
            new IResearchAgent.Outcome("结论", 3, 2, 100, 50, false, false, null));
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        String status = new ResearchTaskServiceImpl(mapper,
            mock(org.dromara.heron.mapper.HeronDataOpportunityMapper.class), agent, clocks, noDecisions()).run(9L);

        assertEquals(HeronResearchTask.STATUS_CANCELLED, status);
    }

    @Test
    @DisplayName("重跑只把终态打回排队，执行中的不动")
    void requeueOnlyRevertsTerminalStatus() {
        HeronResearchTask finished = new HeronResearchTask();
        finished.setResearchId(9L);
        finished.setStatus(HeronResearchTask.STATUS_SUCCEEDED);
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(9L)).thenReturn(finished);
        when(mapper.update(any(Wrapper.class))).thenReturn(1);
        // trigger 会在后台线程再动一次 mapper，用同步容器避免并发改集合
        List<LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>> chains =
            java.util.Collections.synchronizedList(new ArrayList<>());
        when(mapper.lambda()).thenAnswer(invocation -> {
            var chain = new LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>(mapper);
            chains.add(chain);
            return chain;
        });

        service(mapper).requeue(9L);

        var chain = chains.get(0);
        String where = chain.getCustomSqlSegment();
        assertTrue(where.contains("status"), "要按状态判定：" + where);
        var values = chain.getParamNameValuePairs().values();
        assertTrue(values.contains(HeronResearchTask.STATUS_QUEUED), "没把状态写回排队");
        assertTrue(values.contains(HeronResearchTask.STATUS_SUCCEEDED)
            && values.contains(HeronResearchTask.STATUS_FAILED)
            && values.contains(HeronResearchTask.STATUS_CANCELLED)
            && values.contains(HeronResearchTask.STATUS_PARTIAL), "终态清单不全：" + values);
    }

    @Test
    @DisplayName("取消只把状态写成 cancelled，不动结论与用量")
    void cancelWritesTerminalStatus() {
        HeronResearchTask running = new HeronResearchTask();
        running.setResearchId(9L);
        running.setStatus(HeronResearchTask.STATUS_RUNNING);
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(9L)).thenReturn(running);
        when(mapper.update(any(Wrapper.class))).thenReturn(1);
        List<LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>> chains = new ArrayList<>();
        when(mapper.lambda()).thenAnswer(invocation -> {
            var chain = new LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>(mapper);
            chains.add(chain);
            return chain;
        });

        service(mapper).cancel(9L);

        var chain = chains.get(0);
        assertTrue(chain.getParamNameValuePairs().values().contains(HeronResearchTask.STATUS_CANCELLED),
            "状态没写成 cancelled");
    }
}
