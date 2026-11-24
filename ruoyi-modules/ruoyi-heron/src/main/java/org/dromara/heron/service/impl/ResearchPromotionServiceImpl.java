package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
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
 * <p>闸按顺序卡，任何一道不过都给出明确的拒绝原因（业务规格要求每个失败能回答
 * 「哪条闸 + 哪段输入 + 为什么拒」）：
 * <ol>
 *   <li>决策必须存在且已被采纳——PRODUCE 只是建议，人点了采纳才算数；</li>
 *   <li>决策必须是 {@code PRODUCE}——OBSERVE / REJECT 说的是「先别做」，把它们变成候选等于绕过判断；</li>
 *   <li>决策必须绑定了快照——没有数据版本就无法复核这条选题基于什么；</li>
 *   <li>决策必须来自该研究最新一次执行——同一条选题不该因为重跑在池子里出现两次；</li>
 *   <li>证据链自评完整；不完整时得有人写理由放行，且这份理由要留痕。</li>
 *   <li>核验没判 {@code DISAGREE}；判了同样要有人写理由放行。</li>
 * </ol>
 * 另外同一决策幂等，不重复建候选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchPromotionServiceImpl implements IResearchPromotionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 覆盖理由的长度上限；理由要能读懂，但不该把决策表撑爆。 */
    private static final int MAX_OVERRIDE = 1000;
    /** 研究来源在证据里的标识，用来和热榜来的候选区分；与候选来源列同源，不另立字面量。 */
    static final String SOURCE_KIND = HeronNewsCandidate.SOURCE_RESEARCH;
    /** 研究产出的候选热度：不给满值，让热榜候选在同等条件下优先。 */
    static final long RESEARCH_HEAT = 1L;

    private final HeronContentDecisionMapper decisionMapper;
    private final HeronResearchTaskMapper researchTaskMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final ObjectProvider<Clock> clocks;

    @Override
    public long promote(long decisionId, String verificationOverride, String evidenceOverride) {
        HeronContentDecision decision = decisionMapper.selectById(decisionId);
        if (decision == null) {
            throw new HeronBusinessException(404, "decision_not_found", "决策不存在：" + decisionId);
        }
        if (!HeronContentDecision.STATUS_ACCEPTED.equals(decision.getStatus())) {
            throw new HeronBusinessException(409, "decision_not_accepted",
                "只有已采纳的决策才能提升为候选；当前状态：" + decision.getStatus());
        }
        // 采纳 OBSERVE / REJECT 本身没有意义：那两条说的都是「先别做」，
        // 允许它们变成候选等于绕过判断，人点一下采纳就把 Agent 的否定改成了肯定
        if (!HeronContentDecision.DECISION_PRODUCE.equals(decision.getDecisionType())) {
            throw new HeronBusinessException(409, "decision_not_producible",
                "只有「建议生产」的判断能提升为候选；这条是 " + decision.getDecisionType());
        }
        if (decision.getEvidenceRef() == null || decision.getEvidenceRef().isBlank()) {
            // 没有快照标识的结论事后无法复核，不该进业务
            throw new HeronBusinessException(409, "decision_without_snapshot",
                "这条决策没有绑定数据快照，无法复核，不能提升为候选");
        }

        HeronResearchTask research = researchTaskMapper.selectById(decision.getResearchId());

        // 只认这条研究最新一次执行的判断：旧 attempt 的判断一旦也能提升，
        // 同一条选题就会在池子里出现两次，自动模式下各产一条视频。
        // 用任务行的 attempt_no 判「最新」——领取时就会递增，不必再查一遍决策表。
        Integer latestAttempt = research == null ? null : research.getAttemptNo();
        if (latestAttempt != null && decision.getAttemptNo() != null
            && decision.getAttemptNo() < latestAttempt) {
            throw new HeronBusinessException(409, "decision_superseded",
                "这条判断来自第 " + decision.getAttemptNo() + " 次执行，该研究已跑到第 "
                    + latestAttempt + " 次，只能提升最新一次的判断");
        }

        String dedupKey = dedupKey(decisionId);
        HeronNewsCandidate existing = candidateMapper.selectOne(QueryBuilder
            .lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getDedupKey, dedupKey)
            .last("LIMIT 1")
            .build());
        if (existing != null) {
            return existing.getId();
        }

        // 证据链自己都说不上完整：它声称查过的引用在本次 attempt 的账本里找不到。
        // 可能是跨 attempt 的会话记忆，也可能是编造——两种情况都得有人看一眼再放行。
        if (Boolean.FALSE.equals(evidenceComplete(research))) {
            recordOverride(decisionId, evidenceOverride, HeronContentDecision::getEvidenceOverrideReason,
                "evidence_incomplete",
                "这条判断的证据链不完整：模型声称查过的引用在本次执行记录里找不到，需说明理由才能提升");
            log.warn("决策 {} 的证据链不完整被人工放行：{}", decisionId, evidenceOverride.trim());
        }

        // 核验判了冲突就默认拦住：研究员说值得做、核验员说不对，这种分歧不该靠「人点了采纳」默默抹平
        if (HeronContentDecision.VERDICT_DISAGREE.equals(decision.getVerificationVerdict())) {
            recordOverride(decisionId, verificationOverride, HeronContentDecision::getVerificationOverrideReason,
                "verification_disagree",
                "独立核验与研究员结论冲突，需说明理由才能提升");
            log.warn("决策 {} 的核验冲突被人工覆盖：{}", decisionId, verificationOverride.trim());
        }

        // 显式走 selectById(long)：这个 mapper 自带该重载（按机会 id 查），
        // 传 Long 对象会落到 BaseMapper 的 Serializable 版本，是另一条路径
        HeronDataOpportunity opportunity = decision.getOpportunityId() == null
            ? null : opportunityMapper.selectById(decision.getOpportunityId().longValue());
        String title = title(decision, opportunity);

        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(IdUtil.getSnowflakeNextId());
        candidate.setTitle(title);
        candidate.setSummary(truncate(decision.getDecisionReason(), 500));
        candidate.setNormalizedTitle(title == null ? "" : title.trim().toLowerCase());
        candidate.setDedupKey(dedupKey);
        candidate.setHitCount(1L);
        candidate.setHeatScore(RESEARCH_HEAT);
        candidate.setFirstSeenAt(now());
        candidate.setLastSeenAt(now());
        candidate.setEvidence(evidence(decision, research, opportunity));
        candidate.setSourceKind(HeronNewsCandidate.SOURCE_RESEARCH);
        // 来源 key 落列而不是只留在证据里：自动准入要按源查，解析证据会随形状变化静默失效
        candidate.setSourceKey(opportunity == null ? null : opportunity.getSourceKey());
        candidate.setResearchId(decision.getResearchId());
        candidate.setCreateTime(now());
        candidate.setUpdateTime(now());
        if (candidateMapper.insert(candidate) != 1) {
            throw new HeronBusinessException(409, "candidate_write", "候选保存失败");
        }
        log.info("决策 {} 已提升为候选 {}（机会 {}）", decisionId, candidate.getId(),
            decision.getOpportunityId());
        return candidate.getId();
    }

    /**
     * 研究报告自评的证据链是否完整。
     *
     * @return null 表示这次研究没有证据链（L1 不取数，本来就没有）；否则是它的自评
     */
    private static Boolean evidenceComplete(HeronResearchTask research) {
        if (research == null || research.getEvidenceJson() == null || research.getEvidenceJson().isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(research.getEvidenceJson()).path("complete").asBoolean(false);
        } catch (RuntimeException e) {
            // 解析不了就按不完整处理：查不清依据的判断不该默认放行
            return false;
        }
    }

    /** 人工放行一道阻塞闸：没理由就拒，理由写不进去也拒。 */
    private void recordOverride(long decisionId, String reason,
                                SFunction<HeronContentDecision, ?> column, String code, String message) {
        if (reason == null || reason.isBlank()) {
            throw new HeronBusinessException(409, code, message);
        }
        int recorded = decisionMapper.lambda()
            .set(column, truncate(reason.trim(), MAX_OVERRIDE))
            .set(HeronContentDecision::getUpdateTime, now())
            .eq(HeronContentDecision::getId, decisionId)
            .updateCount();
        if (recorded == 0) {
            // 留痕写不进去就不放行：放行了却查不到是谁为什么放的，等于没有门禁
            throw new HeronBusinessException(409, "override_not_recorded",
                "放行理由没有写入决策，本次提升已拒绝");
        }
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
        item.put("decisionId", String.valueOf(decision.getId()));
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
