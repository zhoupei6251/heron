package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 提升的三道闸：没采纳不给提升、没快照不给提升、同一决策不重复建。
 *
 * <p>第一二道是业务规格要的「哪条闸 + 为什么拒」；第三道是幂等。
 */
@DisplayName("决策提升为候选：三道闸与幂等")
class ResearchPromotionServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        var assistant = new MapperBuilderAssistant(configuration, "candidate");
        TableInfoHelper.initTableInfo(assistant, HeronNewsCandidate.class);
    }

    @SuppressWarnings("unchecked")
    private static ResearchPromotionServiceImpl service(HeronContentDecisionMapper decisions,
                                                        HeronNewsCandidateMapper candidates) {
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);
        return new ResearchPromotionServiceImpl(decisions,
            mock(HeronResearchTaskMapper.class), mock(HeronDataOpportunityMapper.class), candidates, clocks);
    }

    private static HeronContentDecision decision(String status, String sha) {
        HeronContentDecision row = new HeronContentDecision();
        row.setDecisionId(1L);
        row.setResearchId(9L);
        row.setAttemptNo(1);
        row.setOpportunityId(900L);
        row.setDecisionType("PRODUCE");
        row.setDecisionReason("人民币同步走强，澳元领跌");
        row.setStatus(status);
        row.setEvidenceRef(sha);
        row.setModelId("deepseek");
        row.setPromptVersion("abc123");
        return row;
    }

    @Test
    @DisplayName("未采纳的决策不能提升")
    void rejectsUnaccepted() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("proposed", "sha1"));

        var ex = assertThrows(HeronBusinessException.class,
            () -> service(decisions, mock(HeronNewsCandidateMapper.class)).promote(1L));
        assertTrue(ex.getMessage().contains("已采纳"), ex.getMessage());
    }

    /** 没有快照标识的结论事后无法复核，不该进业务。 */
    @Test
    @DisplayName("没有绑定快照的决策不能提升")
    void rejectsWithoutSnapshot() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", null));

        var ex = assertThrows(HeronBusinessException.class,
            () -> service(decisions, mock(HeronNewsCandidateMapper.class)).promote(1L));
        assertEquals("decision_without_snapshot", ex.getCode());
    }

    @Test
    @DisplayName("已采纳且有快照时落一条候选，来源标为 research")
    void createsCandidate() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", "sha-abc"));
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);

        assertNotNull(service(decisions, candidates).promote(1L));

        var captor = org.mockito.ArgumentCaptor.forClass(HeronNewsCandidate.class);
        verify(candidates).insert(captor.capture());
        HeronNewsCandidate row = captor.getValue();
        assertTrue(row.getEvidence().contains("\"kind\":\"research\""), row.getEvidence());
        assertTrue(row.getEvidence().contains("sha-abc"));
        assertEquals(1L, row.getHeatScore(), "研究候选不给满热度，让热榜候选同等条件下优先");
        assertNotNull(row.getDedupKey());
    }

    @Test
    @DisplayName("同一决策重复提升返回同一个候选")
    void idempotent() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", "sha-abc"));
        HeronNewsCandidate existing = new HeronNewsCandidate();
        existing.setCandidateId(77L);
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(existing);

        assertEquals(77L, service(decisions, candidates).promote(1L));
        verify(candidates, never()).insert(any(HeronNewsCandidate.class));
    }

    @Test
    @DisplayName("机会有标题时用标题，没有时退回理由截断")
    void titleFromOpportunity() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", "sha"));
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);
        HeronDataOpportunityMapper opportunities = mock(HeronDataOpportunityMapper.class);
        HeronDataOpportunity opp = new HeronDataOpportunity();
        opp.setHeadline("澳元兑人民币创窗口新低");
        when(opportunities.selectById(900L)).thenReturn(opp);  // 这个 mapper 自带 selectById(long) 重载
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);

        new ResearchPromotionServiceImpl(decisions, mock(HeronResearchTaskMapper.class),
            opportunities, candidates, clocks).promote(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(HeronNewsCandidate.class);
        verify(candidates).insert(captor.capture());
        assertEquals("澳元兑人民币创窗口新低", captor.getValue().getTitle());
    }
}
