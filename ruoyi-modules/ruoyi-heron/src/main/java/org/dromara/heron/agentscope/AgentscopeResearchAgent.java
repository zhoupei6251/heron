package org.dromara.heron.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.service.IResearchAgent;

import java.time.Clock;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * {@link IResearchAgent} 的 AgentScope 实现：建会话、挂账本、跑一轮、收文本。
 *
 * <p>超时是兜底，不是预算：真正的闸在 {@link ResearchBudget}（工具次数 + token + 时长）与 {@code maxIters}（轮数）。
 * {@code block} 比预算多留一分钟，让模型有机会把「预算到点」总结成结论。
 */
public class AgentscopeResearchAgent implements IResearchAgent {

    private static final Duration GRACE = Duration.ofMinutes(1);

    private final ResearchAgentFactory factory;
    private final HeronAgentToolCallMapper toolCallMapper;
    private final Clock clock;
    private final int maxToolCalls;
    private final long maxTokens;
    private final Duration maxDuration;

    public AgentscopeResearchAgent(ResearchAgentFactory factory, HeronAgentToolCallMapper toolCallMapper,
                                   Clock clock, int maxToolCalls, long maxTokens, Duration maxDuration) {
        this.factory = factory;
        this.toolCallMapper = toolCallMapper;
        this.clock = clock;
        this.maxToolCalls = maxToolCalls;
        this.maxTokens = maxTokens;
        this.maxDuration = maxDuration;
    }

    @Override
    public Outcome research(String sessionId, long researchId, int attemptNo, String goal,
                            BooleanSupplier cancelled) {
        ResearchBudget budget = new ResearchBudget(maxToolCalls, maxTokens, maxDuration, clock, cancelled);
        ToolCallLedger ledger = new ToolCallLedger(researchId, attemptNo, toolCallMapper, clock, budget);
        try (HarnessAgent agent = factory.create(sessionId, budget, ledger)) {
            Msg reply = agent.call(goal).block(maxDuration.plus(GRACE));
            String text = reply == null ? "" : reply.getTextContent();
            int rounds = ledger.rounds();
            // 框架到 maxIters 就不再推理、直接转去总结，所以轮数跑满就等于被上限截断
            return new Outcome(text, rounds, budget.toolUsed(),
                budget.inputTokens(), budget.outputTokens(),
                budget.exceeded(), rounds >= factory.maxIters());
        }
    }
}
