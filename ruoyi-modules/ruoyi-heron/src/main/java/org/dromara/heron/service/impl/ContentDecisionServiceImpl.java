package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.bo.ContentDecisionQueryBo;
import org.dromara.heron.domain.vo.ContentDecisionVo;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.IContentDecisionService;
import org.dromara.heron.service.IResearchPromotionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容决策：从研究报告落决策，并记录人的裁决。
 *
 * <p>决策表存的是快照而不是引用：研究报告会被重跑覆盖，而「当时人看到的是哪一版判断」
 * 不该被后来的重跑改写。所以这里把 decision / reason / 快照标识一并冻结下来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentDecisionServiceImpl implements IContentDecisionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEFAULT_REPORT_VERSION = "1.0";
    private static final int MAX_REASON = 1000;
    private static final int MAX_NOTE = 1000;

    private final HeronContentDecisionMapper decisionMapper;
    private final HeronResearchTaskMapper researchTaskMapper;
    private final IResearchPromotionService promotions;
    private final ObjectProvider<Clock> clocks;

    @Override
    public Long recordFromResearch(long researchId, int attemptNo) {
        HeronResearchTask task = researchTaskMapper.selectById(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        HeronContentDecision existing = decisionMapper.selectOne(QueryBuilder
            .lambda(HeronContentDecision.class)
            .eq(HeronContentDecision::getResearchId, researchId)
            .eq(HeronContentDecision::getAttemptNo, attemptNo)
            .last("LIMIT 1")
            .build());
        if (existing != null) {
            return existing.getDecisionId();
        }

        JsonNode report = task.getReportJson() == null ? null : JSON.readTree(task.getReportJson());
        String decision = report == null ? null : text(report, "decision");
        if (decision == null || decision.isBlank()) {
            // 模型没给判断就没有决策可落：编一条 OBSERVE 等于替它做判断
            return null;
        }

        HeronContentDecision row = new HeronContentDecision();
        row.setDecisionId(IdUtil.getSnowflakeNextId());
        row.setResearchId(researchId);
        row.setAttemptNo(attemptNo);
        row.setOpportunityId(task.getOpportunityId());
        row.setDecisionType(decision);
        row.setConfidence(decimal(report, "confidence"));
        row.setDecisionReason(truncate(text(report, "reason"), MAX_REASON));
        row.setEvidenceRef(blankToNull(report.path("snapshot").path("dataSha256").asString("")));
        row.setReportVersion(report.path("version").asString(DEFAULT_REPORT_VERSION));
        row.setPromptVersion(blankToNull(report.path("meta").path("promptVersion").asString("")));
        row.setModelId(blankToNull(report.path("meta").path("model").asString("")));
        row.setStatus(HeronContentDecision.STATUS_PROPOSED);
        row.setCreateTime(now());
        row.setUpdateTime(now());
        try {
            decisionMapper.insert(row);
            return row.getDecisionId();
        } catch (DuplicateKeyException e) {
            // 并发触发时后到者不重复落，回读已存在的那条
            HeronContentDecision raced = decisionMapper.selectOne(QueryBuilder
                .lambda(HeronContentDecision.class)
                .eq(HeronContentDecision::getResearchId, researchId)
                .eq(HeronContentDecision::getAttemptNo, attemptNo)
                .last("LIMIT 1")
                .build());
            return raced == null ? null : raced.getDecisionId();
        }
    }

    @Override
    public PageResult<ContentDecisionVo> queryPageList(ContentDecisionQueryBo bo, PageQuery pageQuery) {
        var wrapper = QueryBuilder.lambda(HeronContentDecision.class)
            .eqIfPresent(HeronContentDecision::getOpportunityId, bo.getOpportunityId())
            .eqIfText(HeronContentDecision::getDecisionType, bo.getDecisionType())
            .eqIfText(HeronContentDecision::getStatus, bo.getStatus())
            .orderByDesc(HeronContentDecision::getCreateTime)
            .build();
        var page = decisionMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public ContentDecisionVo queryById(long decisionId) {
        ContentDecisionVo vo = decisionMapper.selectVoById(decisionId);
        if (vo == null) {
            throw new HeronBusinessException(404, "decision_not_found", "决策不存在：" + decisionId);
        }
        return vo;
    }

    @Override
    public ContentDecisionVo decide(long decisionId, boolean accepted, String note) {
        HeronContentDecision existing = decisionMapper.selectById(decisionId);
        if (existing == null) {
            throw new HeronBusinessException(404, "decision_not_found", "决策不存在：" + decisionId);
        }
        String reason = note == null ? null : note.trim();
        if (!accepted && (reason == null || reason.isEmpty())) {
            // 否决必须留理由：说不出为什么否决的否决，事后没法复盘，也没法反馈给模型
            throw new HeronBusinessException(400, "invalid_argument", "人工否决必须填写理由");
        }
        long actor = currentUserId();
        LocalDateTime now = now();
        // 只认待处理：已裁决的那条不能被后一次点击改写，决策是历史事实
        int updated = decisionMapper.lambda()
            .set(HeronContentDecision::getStatus,
                accepted ? HeronContentDecision.STATUS_ACCEPTED : HeronContentDecision.STATUS_REJECTED)
            .set(HeronContentDecision::getDecidedBy, actor)
            .set(HeronContentDecision::getDecidedAt, now)
            .set(HeronContentDecision::getDecisionNote, truncate(reason, MAX_NOTE))
            .set(HeronContentDecision::getUpdateTime, now)
            .eq(HeronContentDecision::getDecisionId, decisionId)
            .eq(HeronContentDecision::getStatus, HeronContentDecision.STATUS_PROPOSED)
            .updateCount();
        if (updated == 0) {
            HeronContentDecision current = decisionMapper.selectById(decisionId);
            throw new HeronBusinessException(409, "decision_already_decided",
                "这条决策已经裁决过，不能改写；当前状态：" + (current == null ? "未知" : current.getStatus()));
        }
        return queryById(decisionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long acceptAndPromote(long decisionId, String note, String verificationOverride,
                                 String evidenceOverride) {
        // 同一个事务：任一环节被拒，采纳也一起回滚，不留「已采纳但没候选」的半截状态
        decide(decisionId, true, note);
        return promotions.promote(decisionId, verificationOverride, evidenceOverride);
    }

    @Override
    public HeronContentDecision findDecisionById(long decisionId) {
        return decisionMapper.selectById(decisionId);
    }

    @Override
    public void attachVerification(long decisionId, String verdict, String reason, String conflictsJson) {
        decisionMapper.lambda()
            .set(HeronContentDecision::getVerificationVerdict, verdict)
            .set(HeronContentDecision::getVerificationReason, truncate(reason, MAX_REASON))
            .set(HeronContentDecision::getVerificationConflicts, conflictsJson)
            .set(HeronContentDecision::getUpdateTime, now())
            .eq(HeronContentDecision::getDecisionId, decisionId)
            .update();
    }

    @Override
    public HeronContentDecision findExistingDecision(long opportunityId) {
        return decisionMapper.selectOne(QueryBuilder
            .lambda(HeronContentDecision.class)
            .eq(HeronContentDecision::getOpportunityId, opportunityId)
            .in(HeronContentDecision::getStatus,
                List.of(HeronContentDecision.STATUS_PROPOSED, HeronContentDecision.STATUS_ACCEPTED))
            .orderByDesc(HeronContentDecision::getCreateTime)
            .last("LIMIT 1")
            .build());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString("");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String raw = text(node, field);
        return raw == null || raw.isBlank() ? null : new BigDecimal(raw);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long currentUserId() {
        try {
            Long userId = LoginHelper.getUserId();
            return userId == null ? 0L : userId;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private LocalDateTime now() {
        Clock clock = clocks.getIfAvailable();
        return LocalDateTime.now(clock == null ? Clock.systemDefaultZone() : clock);
    }
}
