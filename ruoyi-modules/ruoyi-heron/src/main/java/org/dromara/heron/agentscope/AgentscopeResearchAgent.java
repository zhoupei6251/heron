package org.dromara.heron.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronResearchSnapshotMapper;
import org.dromara.heron.service.IResearchAgent;
import org.dromara.heron.service.impl.OpenAiModelClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Clock;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link IResearchAgent} 的 AgentScope 实现：建会话、挂账本、跑一轮、收文本。
 *
 * <p>超时是兜底，不是预算：真正的闸在 {@link ResearchBudget}（工具次数 + token + 时长）与 {@code maxIters}（轮数）。
 * {@code block} 比预算多留一分钟，让模型有机会把「预算到点」总结成结论。
 */
@Slf4j
public class AgentscopeResearchAgent implements IResearchAgent {

    private static final Duration GRACE = Duration.ofMinutes(1);
    private static final String AGENT_VERSION = "agentscope-2.0.4";
    /** 核验的账本尝试号偏移：与研究员的 attempt 分开，谁查了什么一眼可辨。 */
    private static final int VERIFY_ATTEMPT_OFFSET = 1000;
    /** 机会概要单字段上限：信号是整段 JSON，原样塞进去会挤掉判断本身的注意力。 */
    private static final int MAX_BRIEF_FIELD = 1200;
    /** L1 的输出契约：只认这两行。没按格式回就不记结论，绝不替模型编一条。 */
    private static final Pattern L1_DECISION =
        Pattern.compile("DECISION\\s*[:：]\\s*(OBSERVE|REJECT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern L1_REASON =
        Pattern.compile("REASON\\s*[:：]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    /** L1 的系统提示词：只做摘要，不做调查，也不假装查过数据。 */
    private static final String SYSTEM_PROMPT_L1 = """
        你是 Heron 的数据摘要员。你拿不到实时数据，只能基于给到的机会信息做概括。

        必须遵守：
        - 不要编造任何数字。给到的信息里没有的，就说没有。
        - 你的结论只用于粗筛，不会直接进入生产，所以只能给 OBSERVE 或 REJECT——
          没有查过数据就不该说「值得做」，写 PRODUCE 会被丢弃。
        - 输出格式固定，只输出这两行，不要加别的内容：
          DECISION: OBSERVE 或 DECISION: REJECT
          REASON: 一句话说明依据，不超过 60 字
        """;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ResearchAgentFactory factory;
    private final HeronAgentToolCallMapper toolCallMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final HeronResearchSnapshotMapper snapshotMapper;
    private final OpenAiModelClient summarizer;
    private final Clock clock;
    private final int maxToolCalls;
    private final long maxTokens;
    private final Duration maxDuration;

    public AgentscopeResearchAgent(ResearchAgentFactory factory, HeronAgentToolCallMapper toolCallMapper,
                                   HeronDataOpportunityMapper opportunityMapper,
                                   HeronResearchSnapshotMapper snapshotMapper,
                                   OpenAiModelClient summarizer,
                                   Clock clock, int maxToolCalls, long maxTokens, Duration maxDuration) {
        this.factory = factory;
        this.toolCallMapper = toolCallMapper;
        this.opportunityMapper = opportunityMapper;
        this.snapshotMapper = snapshotMapper;
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
     * 代价是它拿不到实时数据，只能基于机会已有信息作答——所以它的结论从不上生产，
     * 只会以 OBSERVE 或 REJECT 落地。
     *
     * <p>必须给它机会本体：只给「机会 123 在候选里」这种目标，等于让它在不知道判断对象是什么的
     * 情况下判断，只能回一句正确的废话，结构化结论也就永远落不下来。
     */
    private Outcome summarize(Request request, ResearchGrade grade) {
        // L1 不查工具，工具次数上限给 0 会让闸在第一次判断时立刻拦——所以给 1 表示「不打算用」
        ResearchBudget budget = new ResearchBudget(1,
            Math.max(1, Math.round(maxTokens * grade.budgetFactor())),
            grade.maxDuration(maxDuration), clock, request.cancelled());
        String input = l1Prompt(request);
        String text = summarizer.chatWithoutUsage(SYSTEM_PROMPT_L1, input);
        int promptTokens = Math.max(1, estimateTokens(input));
        int replyTokens = estimateTokens(text);
        budget.addUsage(promptTokens, replyTokens);
        ResearchFindings findings = readSummary(text, request.researchId());
        String report = ResearchReport.build(findings, null, opportunitySnapshot(request.opportunityId()),
            new ResearchReport.Meta(summarizer.modelName(), factory.promptVersion(), AGENT_VERSION,
                request.attemptNo()));
        return new Outcome(text == null ? "" : text, 1, 0,
            budget.inputTokens(), budget.outputTokens(), false, false,
            new IResearchAgent.Findings(findings.decisionType(), findings.confidence(),
                findings.submitted(), null, report));
    }

    /** L1 的输入：研究目标 + 机会本身已经算好的内容。 */
    private String l1Prompt(Request request) {
        StringBuilder prompt = new StringBuilder("研究目标：").append(request.goal()).append("\n\n机会概要：\n");
        HeronDataOpportunity row = opportunity(request.opportunityId());
        if (row == null) {
            prompt.append("（库里查不到这条机会，只能按目标判断，结论请保守）\n");
        } else {
            appendBrief(prompt, "标题", row.getHeadline());
            appendBrief(prompt, "数据源", row.getSourceKey());
            appendBrief(prompt, "实体", row.getEntity());
            appendBrief(prompt, "指标", row.getMetric());
            appendBrief(prompt, "形态", row.getShape());
            appendBrief(prompt, "周期", row.getCadence());
            appendBrief(prompt, "窗口点数", row.getWindowPoints());
            appendBrief(prompt, "本期点数", row.getPoints());
            appendBrief(prompt, "区间", row.getPeriodStart() == null && row.getPeriodEnd() == null
                ? null : row.getPeriodStart() + " ~ " + row.getPeriodEnd());
            appendBrief(prompt, "机会评分", row.getScore());
            appendBrief(prompt, "信号", row.getSignals());
        }
        JsonNode snapshot = opportunitySnapshot(request.opportunityId());
        if (snapshot != null) {
            prompt.append("快照：retrieved_at=").append(snapshot.path("retrieved_at").asString(""))
                .append("，data_sha256=").append(snapshot.path("data_sha256").asString(""))
                .append("，source_points=").append(snapshot.path("source_points").asInt(0))
                .append('\n');
        }
        return prompt.toString();
    }

    private static void appendBrief(StringBuilder prompt, String label, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return;
        }
        if (text.length() > MAX_BRIEF_FIELD) {
            text = text.substring(0, MAX_BRIEF_FIELD) + "…";
        }
        prompt.append(label).append('：').append(text).append('\n');
    }

    /** 机会本体；查不到或 mapper 不可用时返回 null，L1 仍可只凭目标作答。 */
    private HeronDataOpportunity opportunity(long opportunityId) {
        if (opportunityMapper == null || opportunityId <= 0) {
            return null;
        }
        try {
            return opportunityMapper.selectById(opportunityId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把 L1 的固定格式回复转成结构化判断。
     *
     * <p>没按格式回就一条都不记：编一条 OBSERVE 等于替模型做判断，
     * 下游会拿着一个模型从没说过的结论继续走，比没有结论更糟。
     *
     * <p>包内可见：单测直接驱动这段解析，不必真的调模型。
     */
    static ResearchFindings readSummary(String text, long researchId) {
        ResearchFindings findings = new ResearchFindings();
        if (text == null || text.isBlank()) {
            return findings;
        }
        Matcher decision = L1_DECISION.matcher(text);
        if (!decision.find()) {
            log.warn("L1 未按契约给出决策，结论不落库 researchId={} 回复={}", researchId, preview(text));
            return findings;
        }
        Matcher reason = L1_REASON.matcher(text);
        findings.record(decision.group(1).toUpperCase(), reason.find() ? reason.group(1).trim() : null,
            null, null, null, null, null);
        return findings;
    }

    private static String preview(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
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
        // 本次 attempt 沿途真正取到的数据：冻结下来，证据链才有具体数据版本可指
        ResearchSnapshots snapshots = snapshotMapper == null
            ? ResearchSnapshots.disabled()
            : new ResearchSnapshots(snapshotMapper, request.researchId(), request.attemptNo());
        Msg reply = grade.usesReActOnly()
            ? runReAct(request, budget, ledger, findings, snapshots)
            : runHarness(request, budget, ledger, findings, snapshots);
        String text = reply == null ? "" : reply.getTextContent();
        int rounds = ledger.rounds();
        // 证据链与报告都要在跑完之后采：账本此时才完整
        String evidence = new EvidenceCollector(toolCallMapper)
            .collect(request.researchId(), request.attemptNo(), findings.cited(), findings.submitted(),
                snapshots.recorded());
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
                         ResearchFindings findings, ResearchSnapshots snapshots) {
        try (ReActAgent agent = factory.createReAct(request.sessionId(), budget, ledger, findings, snapshots)) {
            return agent.call(request.goal()).block(maxDuration.plus(GRACE));
        }
    }

    private Msg runHarness(Request request, ResearchBudget budget, ToolCallLedger ledger,
                           ResearchFindings findings, ResearchSnapshots snapshots) {
        try (HarnessAgent agent = factory.create(request.sessionId(), budget, ledger, findings, snapshots)) {
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
