package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.IResearchPromotionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已采纳决策 → 选题候选。
 *
 * <p>三道闸按顺序卡，任何一道不过都给出明确的拒绝原因（业务规格要求每个失败能回答
 * 「哪条闸 + 哪段输入 + 为什么拒」）：
 * <ol>
 *   <li>决策必须存在且已被采纳——PRODUCE 只是建议，人点了采纳才算数；</li>
 *   <li>决策必须绑定了快照——没有数据版本就无法复核这条选题基于什么；</li>
 *   <li>同一决策幂等，不重复建候选。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchPromotionServiceImpl implements IResearchPromotionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 研究来源在证据里的标识，用来和热榜来的候选区分。 */
    static final String SOURCE_KIND = "research";
    /** 研究产出的候选热度：不给满值，让热榜候选在同等条件下优先。 */
    static final long RESEARCH_HEAT = 1L;

    private final HeronContentDecisionMapper decisionMapper;
    private final HeronResearchTaskMapper researchTaskMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final ObjectProvider<Clock> clocks;

    @Override
    public long promote(long decisionId) {
        HeronContentDecision decision = decisionMapper.selectById(decisionId);
        if (decision == null) {
            throw new HeronBusinessException(404, "decision_not_found", "决策不存在：" + decisionId);
        }
        if (!HeronContentDecision.STATUS_ACCEPTED.equals(decision.getStatus())) {
            throw new HeronBusinessException(409, "decision_not_accepted",
                "只有已采纳的决策才能提升为候选；当前状态：" + decision.getStatus());
        }
        if (decision.getEvidenceRef() == null || decision.getEvidenceRef().isBlank()) {
            // 没有快照标识的结论事后无法复核，不该进业务
            throw new HeronBusinessException(409, "decision_without_snapshot",
                "这条决策没有绑定数据快照，无法复核，不能提升为候选");
        }

        String dedupKey = dedupKey(decisionId);
        HeronNewsCandidate existing = candidateMapper.selectOne(QueryBuilder
            .lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getDedupKey, dedupKey)
            .last("LIMIT 1")
            .build());
        if (existing != null) {
            return existing.getCandidateId();
        }

        HeronResearchTask research = researchTaskMapper.selectById(decision.getResearchId());
        // 显式走 selectById(long)：这个 mapper 自带该重载（按机会 id 查），
        // 传 Long 对象会落到 BaseMapper 的 Serializable 版本，是另一条路径
        HeronDataOpportunity opportunity = decision.getOpportunityId() == null
            ? null : opportunityMapper.selectById(decision.getOpportunityId().longValue());
        String title = title(decision, opportunity);

        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setCandidateId(IdUtil.getSnowflakeNextId());
        candidate.setTitle(title);
        candidate.setSummary(truncate(decision.getDecisionReason(), 500));
        candidate.setNormalizedTitle(title == null ? "" : title.trim().toLowerCase());
        candidate.setDedupKey(dedupKey);
        candidate.setHitCount(1L);
        candidate.setHeatScore(RESEARCH_HEAT);
        candidate.setFirstSeenAt(now());
        candidate.setLastSeenAt(now());
        candidate.setEvidence(evidence(decision, research, opportunity));
        candidate.setCreateTime(now());
        candidate.setUpdateTime(now());
        if (candidateMapper.insert(candidate) != 1) {
            throw new HeronBusinessException(409, "candidate_write", "候选保存失败");
        }
        log.info("决策 {} 已提升为候选 {}（机会 {}）", decisionId, candidate.getCandidateId(),
            decision.getOpportunityId());
        return candidate.getCandidateId();
    }

    /** 标题优先用机会标题；没有机会时退回决策理由的截断，不要让候选没有标题。 */
    private static String title(HeronContentDecision decision, HeronDataOpportunity opportunity) {
        if (opportunity != null && opportunity.getHeadline() != null && !opportunity.getHeadline().isBlank()) {
            return truncate(opportunity.getHeadline(), 200);
        }
        return truncate(decision.getDecisionReason(), 200);
    }

    /**
     * 证据：把「这条候选从哪来」写清楚。
     *
     * <p>热榜候选的证据是抓取项；研究候选的证据是研究结论与它依据的快照。
     * 结构对齐（items + evidenceTruncated），下游不用为两种来源写两套解析。
     */
    private static String evidence(HeronContentDecision decision, HeronResearchTask research,
                                   HeronDataOpportunity opportunity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", SOURCE_KIND);
        item.put("decisionId", String.valueOf(decision.getDecisionId()));
        item.put("researchId", String.valueOf(decision.getResearchId()));
        item.put("attemptNo", decision.getAttemptNo());
        item.put("decisionType", decision.getDecisionType());
        item.put("confidence", decision.getConfidence() == null ? null : decision.getConfidence().toPlainString());
        item.put("snapshotSha256", decision.getEvidenceRef());
        item.put("modelId", decision.getModelId());
        item.put("promptVersion", decision.getPromptVersion());
        if (opportunity != null) {
            item.put("sourceKey", opportunity.getSourceKey());
            item.put("entity", opportunity.getEntity());
            item.put("metric", opportunity.getMetric());
            item.put("periodStart", opportunity.getPeriodStart());
            item.put("periodEnd", opportunity.getPeriodEnd());
        }
        if (research != null) {
            item.put("goal", research.getGoal());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("items", List.of(item));
        root.put("evidenceTruncated", false);
        return JSON.writeValueAsString(root);
    }

    /** 同一决策只产出一个候选；跨天重跑会产生新决策，那是新的一条选题判断。 */
    private static String dedupKey(long decisionId) {
        return DigestUtil.sha256Hex(SOURCE_KIND + "|" + decisionId);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private LocalDateTime now() {
        Clock clock = clocks.getIfAvailable();
        return LocalDateTime.now(clock == null ? Clock.systemDefaultZone() : clock);
    }
}
