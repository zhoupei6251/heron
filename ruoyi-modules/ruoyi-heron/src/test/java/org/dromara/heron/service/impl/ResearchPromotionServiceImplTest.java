package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.vo.ContentDecisionVo;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

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
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "decision"), HeronContentDecision.class);
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
            () -> service(decisions, mock(HeronNewsCandidateMapper.class)).promote(1L, null, null));
        assertTrue(ex.getMessage().contains("已采纳"), ex.getMessage());
    }

    /** 没有快照标识的结论事后无法复核，不该进业务。 */
    @Test
    @DisplayName("没有绑定快照的决策不能提升")
    void rejectsWithoutSnapshot() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", null));

        var ex = assertThrows(HeronBusinessException.class,
            () -> service(decisions, mock(HeronNewsCandidateMapper.class)).promote(1L, null, null));
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

        assertNotNull(service(decisions, candidates).promote(1L, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(HeronNewsCandidate.class);
        verify(candidates).insert(captor.capture());
        HeronNewsCandidate row = captor.getValue();
        assertTrue(row.getEvidence().contains("\"kind\":\"research\""), row.getEvidence());
        assertTrue(row.getEvidence().contains("sha-abc"));
        assertEquals(HeronNewsCandidate.SOURCE_RESEARCH, row.getSourceKind(),
            "来源要落在列上：自动模式靠它判准入，靠证据 JSON 推会静默失效");
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

        assertEquals(77L, service(decisions, candidates).promote(1L, null, null));
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
            opportunities, candidates, clocks).promote(1L, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(HeronNewsCandidate.class);
        verify(candidates).insert(captor.capture());
        assertEquals("澳元兑人民币创窗口新低", captor.getValue().getTitle());
    }

    /**
     * OBSERVE / REJECT 说的都是「先别做」。允许它们提升，等于人点一下采纳
     * 就把 Agent 的否定改成了肯定，这条判断原本的意思就没了。
     */
    @Test
    @DisplayName("非 PRODUCE 的判断不能提升")
    void rejectsNonProduce() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision row = decision("accepted", "sha1");
        row.setDecisionType("OBSERVE");
        when(decisions.selectById(1L)).thenReturn(row);

        var ex = assertThrows(HeronBusinessException.class,
            () -> service(decisions, mock(HeronNewsCandidateMapper.class)).promote(1L, null, null));
        assertEquals("decision_not_producible", ex.getCode());
    }

    /** 研究员说值得做、核验员说不对：这种分歧不该靠「人点了采纳」默默抹平。 */
    @Test
    @DisplayName("核验冲突且没给理由：拦下，不建候选")
    void blocksDisagreeWithoutOverride() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision row = decision("accepted", "sha1");
        row.setVerificationVerdict(HeronContentDecision.VERDICT_DISAGREE);
        when(decisions.selectById(1L)).thenReturn(row);
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);

        var ex = assertThrows(HeronBusinessException.class,
            () -> service(decisions, candidates).promote(1L, "   ", null));
        assertEquals("verification_disagree", ex.getCode());
        verify(candidates, never()).insert(any(HeronNewsCandidate.class));
    }

    @Test
    @DisplayName("核验冲突给了理由：放行，并把理由写回决策")
    void recordsOverrideReason() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision row = decision("accepted", "sha1");
        row.setVerificationVerdict(HeronContentDecision.VERDICT_DISAGREE);
        when(decisions.selectById(1L)).thenReturn(row);
        when(decisions.update(any(Wrapper.class))).thenReturn(1);
        List<LambdaCrudChainWrapper<HeronContentDecision, ContentDecisionVo>> chains = new ArrayList<>();
        when(decisions.lambda()).thenAnswer(invocation -> {
            var chain = new LambdaCrudChainWrapper<HeronContentDecision, ContentDecisionVo>(decisions);
            chains.add(chain);
            return chain;
        });
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);

        assertNotNull(service(decisions, candidates).promote(1L, " 已电话核实源站口径 ", null));

        assertEquals(1, chains.size(), "理由要写回决策");
        assertTrue(chains.get(0).getParamNameValuePairs().values().contains("已电话核实源站口径"),
            "留痕里没带理由：" + chains.get(0).getParamNameValuePairs().values());
        verify(candidates).insert(any(HeronNewsCandidate.class));
    }

    private static ResearchPromotionServiceImpl withResearch(HeronContentDecisionMapper decisions,
                                                            HeronNewsCandidateMapper candidates,
                                                            HeronResearchTaskMapper research) {
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable()).thenReturn(null);
        return new ResearchPromotionServiceImpl(decisions, research,
            mock(HeronDataOpportunityMapper.class), candidates, clocks);
    }

    private static org.dromara.heron.domain.HeronResearchTask researchWithEvidence(String evidenceJson) {
        var row = new org.dromara.heron.domain.HeronResearchTask();
        row.setResearchId(9L);
        row.setEvidenceJson(evidenceJson);
        return row;
    }

    private static org.dromara.heron.domain.HeronResearchTask researchAtAttempt(int attemptNo,
                                                                               String evidenceJson) {
        var row = researchWithEvidence(evidenceJson);
        row.setAttemptNo(attemptNo);
        return row;
    }

    /**
     * 研究重跑会产出新的 attempt。旧 attempt 的判断若也能提升，同一条选题
     * 会在池子里出现两次，自动模式下各产一条视频——那是重复生产，不是多一种选择。
     */
    @Test
    @DisplayName("旧 attempt 的判断不能提升")
    void rejectsSupersededAttempt() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision row = decision("accepted", "sha1");
        row.setAttemptNo(1);
        when(decisions.selectById(1L)).thenReturn(row);
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(researchAtAttempt(3, "{\"complete\":true}"));

        var ex = assertThrows(HeronBusinessException.class,
            () -> withResearch(decisions, candidates, research).promote(1L, null, null));

        assertEquals("decision_superseded", ex.getCode());
        verify(candidates, never()).insert(any(HeronNewsCandidate.class));
    }

    @Test
    @DisplayName("最新 attempt 的判断可以提升")
    void admitsLatestAttempt() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        HeronContentDecision row = decision("accepted", "sha1");
        row.setAttemptNo(3);
        when(decisions.selectById(1L)).thenReturn(row);
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(researchAtAttempt(3, "{\"complete\":true}"));

        assertNotNull(withResearch(decisions, candidates, research).promote(1L, null, null));
        verify(candidates).insert(any(HeronNewsCandidate.class));
    }

    /**
     * 证据链自己都说不上完整：它声称查过的引用在本次执行记录里找不到。
     * 可能是跨 attempt 的会话记忆，也可能是编造——两种情况都不该由系统默认放行。
     */
    @Test
    @DisplayName("证据链不完整且没给理由：拦下，不建候选")
    void blocksIncompleteEvidenceWithoutOverride() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", "sha1"));
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(researchWithEvidence("{\"complete\":false}"));

        var ex = assertThrows(HeronBusinessException.class,
            () -> withResearch(decisions, candidates, research).promote(1L, null, "   "));

        assertEquals("evidence_incomplete", ex.getCode());
        verify(candidates, never()).insert(any(HeronNewsCandidate.class));
    }

    @Test
    @DisplayName("证据链不完整但给了理由：放行并留痕")
    void recordsEvidenceOverride() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", "sha1"));
        when(decisions.update(any(Wrapper.class))).thenReturn(1);
        List<LambdaCrudChainWrapper<HeronContentDecision, ContentDecisionVo>> chains = new ArrayList<>();
        when(decisions.lambda()).thenAnswer(invocation -> {
            var chain = new LambdaCrudChainWrapper<HeronContentDecision, ContentDecisionVo>(decisions);
            chains.add(chain);
            return chain;
        });
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(researchWithEvidence("{\"complete\":false}"));

        assertNotNull(withResearch(decisions, candidates, research)
            .promote(1L, null, " 那几个数字来自同一研究上一轮的查询 "));

        assertTrue(chains.get(0).getParamNameValuePairs().values().contains("那几个数字来自同一研究上一轮的查询"),
            "留痕里没带理由：" + chains.get(0).getParamNameValuePairs().values());
        verify(candidates).insert(any(HeronNewsCandidate.class));
    }

    /** 有证据链且自评完整时不拦：别把正常路径也堵上。 */
    @Test
    @DisplayName("证据链完整：不需要理由")
    void completeEvidenceNeedsNoOverride() {
        HeronContentDecisionMapper decisions = mock(HeronContentDecisionMapper.class);
        when(decisions.selectById(1L)).thenReturn(decision("accepted", "sha1"));
        HeronNewsCandidateMapper candidates = mock(HeronNewsCandidateMapper.class);
        when(candidates.selectOne(any())).thenReturn(null);
        when(candidates.insert(any(HeronNewsCandidate.class))).thenReturn(1);
        HeronResearchTaskMapper research = mock(HeronResearchTaskMapper.class);
        when(research.selectById(9L)).thenReturn(researchWithEvidence("{\"complete\":true}"));

        assertNotNull(withResearch(decisions, candidates, research).promote(1L, null, null));
        verify(candidates).insert(any(HeronNewsCandidate.class));
    }
}
