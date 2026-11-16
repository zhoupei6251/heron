package org.dromara.heron.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.service.IResearchAgent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * {@link IResearchAgent} 的 AgentScope 实现：建会话、挂账本、跑一轮、收文本。
 *
 * <p>超时是兜底，不是预算：真正的闸在 {@link ResearchBudget}（工具次数 + token + 时长）与 {@code maxIters}（轮数）。
 * {@code block} 比预算多留一分钟，让模型有机会把「预算到点」总结成结论。
 */
public class AgentscopeResearchAgent implements IResearchAgent {

    private static final Duration GRACE = Duration.ofMinutes(1);
    private static final String AGENT_VERSION = "agentscope-2.0.4";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ResearchAgentFactory factory;
    private final HeronAgentToolCallMapper toolCallMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final Clock clock;
    private final int maxToolCalls;
    private final long maxTokens;
    private final Duration maxDuration;

    public AgentscopeResearchAgent(ResearchAgentFactory factory, HeronAgentToolCallMapper toolCallMapper,
                                   HeronDataOpportunityMapper opportunityMapper, Clock clock,
                                   int maxToolCalls, long maxTokens, Duration maxDuration) {
        this.factory = factory;
        this.toolCallMapper = toolCallMapper;
        this.opportunityMapper = opportunityMapper;
        this.clock = clock;
        this.maxToolCalls = maxToolCalls;
        this.maxTokens = maxTokens;
        this.maxDuration = maxDuration;
    }

    @Override
    public Outcome research(Request request) {
        ResearchBudget budget = new ResearchBudget(maxToolCalls, maxTokens, maxDuration, clock,
            request.cancelled());
        ResearchFindings findings = new ResearchFindings();
        ToolCallLedger ledger = new ToolCallLedger(request.researchId(), request.attemptNo(),
            toolCallMapper, clock, budget);
        try (HarnessAgent agent = factory.create(request.sessionId(), budget, ledger, findings)) {
            Msg reply = agent.call(request.goal()).block(maxDuration.plus(GRACE));
            String text = reply == null ? "" : reply.getTextContent();
            int rounds = ledger.rounds();
            // 证据链与报告都要在跑完之后采：账本此时才完整
            String evidence = new EvidenceCollector(toolCallMapper)
                .collect(request.researchId(), request.attemptNo(), findings.cited(), findings.submitted());
            String report = ResearchReport.build(findings, evidence,
                opportunitySnapshot(request.opportunityId()),
                new ResearchReport.Meta(factory.modelName(), factory.promptVersion(),
                    AGENT_VERSION, request.attemptNo()));
            // 框架到 maxIters 就不再推理、直接转去总结，所以轮数跑满就等于被上限截断
            return new Outcome(text, rounds, budget.toolUsed(),
                budget.inputTokens(), budget.outputTokens(),
                budget.exceeded(), rounds >= factory.maxIters(),
                new IResearchAgent.Findings(findings.decisionType(), findings.confidence(),
                    findings.submitted(), evidence, report));
        }
    }

    /**
     * 研究依据的那份落库快照。
     *
     * <p>为什么不是研究时现取的那份：快照会变，报告要能证明「结论是基于哪一份数据得出的」，
     * 只有落库的那份带 data_sha256，事后才复核得了。
     */
    private JsonNode opportunitySnapshot(long opportunityId) {
        if (opportunityMapper == null || opportunityId <= 0) {
            return null;
        }
        try {
            HeronDataOpportunity row = opportunityMapper.selectById(opportunityId);
            String json = row == null ? null : row.getSnapshotJson();
            return json == null || json.isBlank() ? null : JSON.readTree(json);
        } catch (RuntimeException e) {
            // 绑定不上快照不该让整次研究失败：报告里会写 bound=false
            return null;
        }
    }
}
