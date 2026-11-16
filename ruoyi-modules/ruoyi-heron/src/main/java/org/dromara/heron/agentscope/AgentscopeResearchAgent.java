package org.dromara.heron.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.service.IResearchAgent;
import org.dromara.heron.service.impl.OpenAiModelClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

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
    /** 核验的账本尝试号偏移：与研究员的 attempt 分开，谁查了什么一眼可辨。 */
    private static final int VERIFY_ATTEMPT_OFFSET = 1000;
    /** L1 的系统提示词：只做摘要，不做调查，也不假装查过数据。 */
    private static final String SYSTEM_PROMPT_L1 = """
        你是 Heron 的数据摘要员。你拿不到实时数据，只能基于给到的信息做概括。

        必须遵守：
        - 不要编造任何数字。没有数据就说没有。
        - 你的结论只用于粗筛，不会直接进入生产。
        - 给出 decision 时只允许 OBSERVE 或 REJECT——没有查过数据就不该说「值得做」。

        用一两句话说明你的判断和依据。
        """;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ResearchAgentFactory factory;
    private final HeronAgentToolCallMapper toolCallMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final OpenAiModelClient summarizer;
    private final Clock clock;
    private final int maxToolCalls;
    private final long maxTokens;
    private final Duration maxDuration;

    public AgentscopeResearchAgent(ResearchAgentFactory factory, HeronAgentToolCallMapper toolCallMapper,
                                   HeronDataOpportunityMapper opportunityMapper, OpenAiModelClient summarizer,
                                   Clock clock, int maxToolCalls, long maxTokens, Duration maxDuration) {
        this.factory = factory;
        this.toolCallMapper = toolCallMapper;
        this.opportunityMapper = opportunityMapper;
        this.summarizer = summarizer;
        this.clock = clock;
        this.maxToolCalls = maxToolCalls;
        this.maxTokens = maxTokens;
        this.maxDuration = maxDuration;
    }

    /** 粗估 token：L1 走的是不记用量的轻量客户端，拿不到供应商口径，只能按字符估。 */
    static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 3);
    }

    @Override
    public Outcome research(Request request) {
        ResearchGrade grade = request.grade() == null ? ResearchGrade.L2 : request.grade();
        if (grade == ResearchGrade.L1) {
            return summarize(request, grade);
        }
        return investigate(request, grade);
    }

    /**
     * L1：一次模型调用，不查工具。
     *
     * <p>这一档的存在意义是**成本**：普通数据摘要不值得起一套 ReAct 循环。
     * 代价是它拿不到实时数据，只能基于目标文本与机会摘要作答——所以它的结论从不上生产，
     * 只会以 OBSERVE 或 REJECT 落地（下游按 grade 判断）。
     */
    private Outcome summarize(Request request, ResearchGrade grade) {
        // L1 不查工具，工具次数上限给 0 会让闸在第一次判断时立刻拦——所以给 1 表示「不打算用」
        ResearchBudget budget = new ResearchBudget(1,
            Math.max(1, Math.round(maxTokens * grade.budgetFactor())),
            grade.maxDuration(maxDuration), clock, request.cancelled());
        String text = summarizer.chatWithoutUsage(SYSTEM_PROMPT_L1, request.goal());
        int promptTokens = Math.max(1, estimateTokens(request.goal()));
        int replyTokens = estimateTokens(text);
        budget.addUsage(promptTokens, replyTokens);
        ResearchFindings findings = new ResearchFindings();
        String report = ResearchReport.build(findings, null, opportunitySnapshot(request.opportunityId()),
            new ResearchReport.Meta(summarizer.modelName(), factory.promptVersion(), AGENT_VERSION,
                request.attemptNo()));
        return new Outcome(text == null ? "" : text, 1, 0,
            budget.inputTokens(), budget.outputTokens(), false, false,
            new IResearchAgent.Findings(null, null, false, null, report));
    }

    /** L2/L3：ReAct 循环查数据。等级只改执行器与预算，不改工具面。 */
    private Outcome investigate(Request request, ResearchGrade grade) {
        ResearchBudget budget = new ResearchBudget(
            (int) Math.round(maxToolCalls * grade.budgetFactor() / 2),
            Math.round(maxTokens * grade.budgetFactor()),
            maxDuration, clock, request.cancelled());
        ResearchFindings findings = new ResearchFindings();
        ToolCallLedger ledger = new ToolCallLedger(request.researchId(), request.attemptNo(),
            toolCallMapper, clock, budget);
        Msg reply = grade.usesReActOnly()
            ? runReAct(request, budget, ledger, findings)
            : runHarness(request, budget, ledger, findings);
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

    private Msg runReAct(Request request, ResearchBudget budget, ToolCallLedger ledger,
                         ResearchFindings findings) {
        try (ReActAgent agent = factory.createReAct(request.sessionId(), budget, ledger, findings)) {
            return agent.call(request.goal()).block(maxDuration.plus(GRACE));
        }
    }

    private Msg runHarness(Request request, ResearchBudget budget, ToolCallLedger ledger,
                           ResearchFindings findings) {
        try (HarnessAgent agent = factory.create(request.sessionId(), budget, ledger, findings)) {
            return agent.call(request.goal()).block(maxDuration.plus(GRACE));
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

    @Override
    public Verification verify(VerifyRequest request) {
        ResearchVerification verification = new ResearchVerification();
        ResearchBudget budget = new ResearchBudget(maxToolCalls, maxTokens, maxDuration, clock,
            request.cancelled());
        // 核验的账本尝试号加偏移：和研究员的调用混在同一个 attempt 里会分不清谁查了什么
        int ledgerAttempt = request.attemptNo() + VERIFY_ATTEMPT_OFFSET;
        ToolCallLedger ledger = new ToolCallLedger(request.researchId(), ledgerAttempt,
            toolCallMapper, clock, budget);
        String prompt = "研究目标：" + request.goal() + "\n\n被核验的结论：\n" + request.claim();
        try (HarnessAgent agent = factory.createVerifier(request.sessionId(), budget, ledger, verification)) {
            agent.call(prompt).block(maxDuration.plus(GRACE));
        }
        ArrayNode conflicts = JSON.createArrayNode();
        verification.conflicts().forEach(conflicts::add);
        return new Verification(verification.verdict(), verification.reason(),
            conflicts.toString(), verification.submitted());
    }
}
