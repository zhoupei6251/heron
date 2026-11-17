package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.vo.ContentDecisionVo;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 决策表的两个边界：报告里没结论就不能落决策（否则等于替模型做判断），
 * 人工否决必须留理由（否则事后没法复盘，也没法反馈给模型）。
 */
@DisplayName("内容决策：从报告落库与人工裁决")
class ContentDecisionServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        var assistant = new MapperBuilderAssistant(configuration, "decision");
        TableInfoHelper.initTableInfo(assistant, HeronContentDecision.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "research"), HeronResearchTask.class);
    }

    @SuppressWarnings("unchecked")
    private static ContentDecisionServiceImpl service(HeronContentDecisionMapper decisions,
                                                      HeronResearchTaskMapper research) {
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);
        return new ContentDecisionServiceImpl(decisions, research,
            mock(org.dromara.heron.service.IResearchPromotionService.class), clocks);
    }

    private static HeronResearchTask withReport(String reportJson) {
        HeronResearchTask task = new HeronResearchTask();
        task.setResearchId(9L);
        task.setOpportunityId(900L);
        task.setReportJson(reportJson);
        return task;
    }

    private static final String REPORT = """
        {"version":"1.0","submitted":true,"decision":"REJECT","reason":"独立信息量为零",
         "confidence":"0.82",
         "snapshot":{"bound":true,"dataSha256":"deadbeef"},
         "meta":{"model":"deepseek-v4.1-flash-economy","promptVersion":"abc123"}}
        """;

    @Test
    @DisplayName("报告有结论时落一条待处理决策，快照标识一并冻结")
    void recordsDecisionFromReport() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectOne(any())).thenReturn(null);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(withReport(REPORT));

        Long id = service(decisions, research).recordFromResearch(9L, 1);

        assertNotNull(id);
        var captor = org.mockito.ArgumentCaptor.forClass(HeronContentDecision.class);
        verify(decisions).insert(captor.capture());
        HeronContentDecision row = captor.getValue();
        assertEquals("REJECT", row.getDecisionType());
        assertEquals(new BigDecimal("0.82"), row.getConfidence());
        assertEquals("独立信息量为零", row.getDecisionReason());
        assertEquals("deadbeef", row.getEvidenceRef(), "快照标识要冻结进决策，报告重跑不该改写它");
        assertEquals("deepseek-v4.1-flash-economy", row.getModelId());
        assertEquals("abc123", row.getPromptVersion());
        assertEquals(HeronContentDecision.STATUS_PROPOSED, row.getStatus());
    }

    @Test
    @DisplayName("报告里没有结论就不落决策：编一条 OBSERVE 等于替模型做判断")
    void noDecisionWithoutVerdict() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectOne(any())).thenReturn(null);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(withReport("{\"version\":\"1.0\",\"submitted\":false}"));

        assertNull(service(decisions, research).recordFromResearch(9L, 1));
        verify(decisions, never()).insert(any(HeronContentDecision.class));
    }

    @Test
    @DisplayName("同一研究同一次执行只落一条")
    void idempotentPerAttempt() {
        HeronContentDecision existing = new HeronContentDecision();
        existing.setDecisionId(77L);
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectOne(any())).thenReturn(existing);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(withReport(REPORT));

        assertEquals(77L, service(decisions, research).recordFromResearch(9L, 1));
        verify(decisions, never()).insert(any(HeronContentDecision.class));
    }

    @Test
    @DisplayName("并发落重复时回读已存在的那条，不抛给调用方")
    void toleratesConcurrentInsert() {
        HeronContentDecision other = new HeronContentDecision();
        other.setDecisionId(88L);
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectOne(any())).thenReturn(null, other);
        when(decisions.insert(any(HeronContentDecision.class))).thenThrow(new DuplicateKeyException("dup"));
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(withReport(REPORT));

        assertEquals(88L, service(decisions, research).recordFromResearch(9L, 1));
    }

    @Test
    @DisplayName("人工否决必须填理由")
    void rejectRequiresNote() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision existing = new HeronContentDecision();
        existing.setDecisionId(1L);
        when(decisions.selectById(1L)).thenReturn(existing);

        var subject = service(decisions, mock(HeronResearchTaskMapper.class));
        assertThrows(HeronBusinessException.class, () -> subject.decide(1L, false, null));
        assertThrows(HeronBusinessException.class, () -> subject.decide(1L, false, "  "));
        verify(decisions, never()).lambda();
    }

    @Test
    @DisplayName("采纳可以不留理由")
    void acceptWithoutNote() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision existing = new HeronContentDecision();
        existing.setDecisionId(1L);
        when(decisions.selectById(1L)).thenReturn(existing);
        var chain = mock(org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper.class);
        when(chain.set(any(), any())).thenReturn(chain);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.updateCount()).thenReturn(1);
        when(decisions.lambda()).thenReturn(chain);
        ContentDecisionVo vo = new ContentDecisionVo();
        vo.setDecisionId(1L);
        vo.setStatus(HeronContentDecision.STATUS_ACCEPTED);
        when(decisions.selectVoById(1L)).thenReturn(vo);

        var result = service(decisions, mock(HeronResearchTaskMapper.class)).decide(1L, true, null);

        assertEquals(HeronContentDecision.STATUS_ACCEPTED, result.getStatus());
        verify(chain, atLeastOnce()).set(any(), any());
    }

    /**
     * 决策一旦裁决就是历史事实。放开改写的话，那条「当时人看过什么、怎么定的」记录
     * 会被后来的点击抹掉，复盘和反馈模型都失去依据。
     */
    @Test
    @DisplayName("已裁决的决策不能重复改写")
    void cannotReworkDecided() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision existing = new HeronContentDecision();
        existing.setDecisionId(1L);
        existing.setStatus(HeronContentDecision.STATUS_ACCEPTED);
        when(decisions.selectById(1L)).thenReturn(existing);
        var chain = mock(org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper.class);
        when(chain.set(any(), any())).thenReturn(chain);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.updateCount()).thenReturn(0);
        when(decisions.lambda()).thenReturn(chain);

        var ex = assertThrows(HeronBusinessException.class,
            () -> service(decisions, mock(HeronResearchTaskMapper.class)).decide(1L, true, null));
        assertEquals("decision_already_decided", ex.getCode());
    }

    /**
     * 采纳与提升必须一起成功或一起失败：只采纳成功会留下「已采纳但没有候选」的半截状态，
     * 用户看到报错，再点一次又被告知已经裁决过。
     */
    @Test
    @DisplayName("采纳并提升：候选 id 原样返回，提升被拒则整体抛出")
    void acceptAndPromoteDelegates() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision existing = new HeronContentDecision();
        existing.setDecisionId(1L);
        when(decisions.selectById(1L)).thenReturn(existing);
        var chain = mock(org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper.class);
        when(chain.set(any(), any())).thenReturn(chain);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.updateCount()).thenReturn(1);
        when(decisions.lambda()).thenReturn(chain);
        ContentDecisionVo vo = new ContentDecisionVo();
        vo.setDecisionId(1L);
        when(decisions.selectVoById(1L)).thenReturn(vo);
        var promotions = mock(org.dromara.heron.service.IResearchPromotionService.class);
        when(promotions.promote(1L, "源站已人工核实", null)).thenReturn(7L);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        long candidateId = new ContentDecisionServiceImpl(decisions,
            mock(HeronResearchTaskMapper.class), promotions, clocks)
            .acceptAndPromote(1L, null, "源站已人工核实", null);

        assertEquals(7L, candidateId);
        verify(promotions).promote(1L, "源站已人工核实", null);
    }
}
