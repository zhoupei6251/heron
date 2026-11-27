package org.dromara.heron.service.impl.shared.research;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import org.dromara.heron.agentscope.ResearchAgentFactory;
import org.dromara.heron.agentscope.ResearchBudget;
import org.dromara.heron.agentscope.ToolCallLedger;
import org.dromara.heron.agentscope.TrendLearnTools;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendTechniqueCard;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

/** 用现有 ResearchAgentFactory 的 L2 ReAct 执行器归纳技法卡。 */
@Service
@RequiredArgsConstructor
public class TrendLearningAgent {

    private static final String PROMPT = "/prompt/trend-learn/technique-card.md";
    private static final Set<String> REQUIRED_TOOLS = Set.of(
        "read_breakdown", "list_profiles", "read_profile", "view_frame");

    private final ObjectProvider<ResearchAgentFactory> factories;
    private final HeronAgentToolCallMapper toolCalls;
    private final HeronProductionProperties production;
    private final Environment environment;
    private final ObjectProvider<Clock> clocks;

    /**
     * 检查 ReAct 执行器是否已装配。
     *
     * @throws HeronBusinessException 503 {@code trend_learn_agent_disabled} 未启用 {@code heron.agent.enabled}
     */
    public void requireAvailable() {
        if (factories.getIfAvailable() == null) {
            throw new HeronBusinessException(503, "trend_learn_agent_disabled",
                "学习归纳需要启用 heron.agent.enabled 以复用现有 ReAct 执行器");
        }
    }

    /**
     * 用 L2 ReAct 执行器归纳一张技法卡：会话内必须实际读取 breakdown、模板清单与 storyboard，
     * 且产出的模板落点要指向真实存在的 Profile。
     *
     * @param breakdown  拆解产物 breakdown.json 路径
     * @param storyboard 拆解产物 storyboard.jpg 路径
     * @param attempt    第几次尝试，用于会话 id 与工具调用账本
     * @return 解析并校验通过的技法卡
     * @throws HeronBusinessException 503 执行器未启用；502 {@code trend_learn_evidence_missing}
     *         未实际读取必需产物，或 {@code trend_learn_card_invalid} 输出不是合法技法卡
     */
    public TrendTechniqueCard learn(HeronTrendVideo video, Path breakdown, Path storyboard, int attempt) {
        requireAvailable();
        ResearchAgentFactory factory = factories.getIfAvailable();
        Clock clock = clocks.getIfAvailable(Clock::systemDefaultZone);
        int toolLimit = environment.getProperty("heron.agent.max-tool-calls", Integer.class, 12);
        long tokenLimit = environment.getProperty("heron.agent.max-tokens", Long.class, 300_000L);
        Duration maxDuration = Duration.ofSeconds(environment.getProperty("heron.agent.max-duration-seconds", Long.class, 300L));
        ResearchBudget budget = new ResearchBudget(toolLimit, tokenLimit, maxDuration, clock, null);
        ToolCallLedger ledger = new ToolCallLedger(video.getId(), attempt, toolCalls, clock, budget);
        TrendLearnTools tools = new TrendLearnTools(production, breakdown, storyboard, budget);
        String sessionId = "trend-learn-" + video.getId() + "-a" + attempt;
        String goal = "作品 ID：" + video.getAwemeId() + "\n作者：" + video.getAuthorNickname()
            + "\n时长：" + video.getDurationMs() + " ms\n摘要：" + video.getDescription()
            + "\n\n请按系统提示完成技法归纳，并只返回固定 JSON。";
        try (ReActAgent agent = factory.createReAct(sessionId, "heron-trend-learn-react",
            resource(PROMPT), tools, budget, ledger)) {
            Msg reply = agent.call(goal).block(maxDuration.plusSeconds(10));
            TrendTechniqueCard card = TrendTechniqueCard.parse(reply == null ? null : reply.getTextContent());
            requireEvidence(ledger.successfulTools());
            validateTemplateFit(card, tools::hasProfile);
            return card;
        } catch (IllegalArgumentException e) {
            throw new HeronBusinessException(502, "trend_learn_card_invalid", "学习 Agent 未输出合法技法卡: " + e.getMessage(), e);
        }
    }

    public static void requireEvidence(Set<String> successfulTools) {
        if (!successfulTools.containsAll(REQUIRED_TOOLS)) {
            throw new HeronBusinessException(502, "trend_learn_evidence_missing",
                "学习 Agent 未实际读取 breakdown、模板与 storyboard，拒绝采纳技法卡");
        }
    }

    public static void validateTemplateFit(TrendTechniqueCard card, Predicate<String> profileExists) {
        String fit = card.heronTemplateFit();
        if (fit.startsWith("无完全匹配")) {
            String[] parts = fit.split("[：:]", 3);
            if (parts.length < 3 || parts[2].isBlank() || !profileExists.test(parts[1].trim())) {
                throw new IllegalArgumentException("无完全匹配时也必须给出真实的最接近模板 key");
            }
            return;
        }
        int separator = fit.indexOf('：');
        if (separator < 0) {
            separator = fit.indexOf(':');
        }
        if (separator <= 0 || separator >= fit.length() - 1
            || !profileExists.test(fit.substring(0, separator).trim())) {
            throw new IllegalArgumentException("heronTemplateFit 必须指向真实模板 key，或明确写无完全匹配");
        }
    }

    private static String resource(String path) {
        try (InputStream stream = TrendLearningAgent.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少学习提示词 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("学习提示词不可读 " + path, e);
        }
    }
}
