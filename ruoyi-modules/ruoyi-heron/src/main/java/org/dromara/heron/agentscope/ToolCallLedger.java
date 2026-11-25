package org.dromara.heron.agentscope;

import cn.hutool.core.util.IdUtil;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import org.dromara.heron.domain.HeronAgentToolCall;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 工具调用账本 + 用量采集。
 *
 * <p>用官方 hook 而不是往工具里埋点：工具是业务代码，记账是横切关注点。
 *
 * <p>不管预算：预算在 {@link ResearchBudget} 里、由工具入口执行。
 * 早先在 hook 里用 {@code stopAgent()} 停，实测挡不住同一轮的并行调用（预算 2 实际执行 5 次）。
 *
 * <p>token 用量在 {@code POST_REASONING} 采：框架把累加好的 {@link ChatUsage} 挂在推理消息上，
 * 这是唯一能在不引中间件的前提下拿到用量的地方。
 *
 * <p>记账失败只记日志、不打断研究：账错了不能把已经跑出来的结论一起扔掉。
 */
public class ToolCallLedger implements Hook {

    private static final int MAX_SUMMARY = 2000;
    /** 键名像凭据就把值打掉；宁可多打，也不要把模型随手编的 key 存进库。 */
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(\"?(?:api[_-]?key|access[_-]?key|token|secret|password|passwd|authorization|cookie)\"?\\s*[:=]\\s*)"
            + "(\"[^\"]*\"|[^,\\s}\\]]+)");

    private final long researchId;
    private final int attemptNo;
    private final HeronAgentToolCallMapper mapper;
    private final Clock clock;
    /** 用于区分「正常执行」与「被预算拒掉」，后者也要留痕。 */
    private final ResearchBudget budget;

    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();
    private final Set<String> successfulTools = ConcurrentHashMap.newKeySet();
    private final AtomicInteger rounds = new AtomicInteger();

    public ToolCallLedger(long researchId, int attemptNo, HeronAgentToolCallMapper mapper,
                          Clock clock, ResearchBudget budget) {
        this.researchId = researchId;
        this.attemptNo = attemptNo;
        this.mapper = mapper;
        this.clock = clock;
        this.budget = budget;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        switch (event.getType()) {
            case PRE_REASONING -> rounds.incrementAndGet();
            case POST_REASONING -> {
                if (event instanceof PostReasoningEvent post) {
                    recordUsage(post);
                }
            }
            case PRE_ACTING -> {
                if (event instanceof PreActingEvent pre) {
                    startedAt.put(pre.getToolUse().getId(), clock.millis());
                }
            }
            case POST_ACTING -> {
                if (event instanceof PostActingEvent post) {
                    record(post);
                }
            }
            default -> {
                // 其它事件不关心
            }
        }
        return Mono.just(event);
    }

    /** 框架会把本轮推理的用量合并进推理消息，取不到就当作供应商没报，不影响研究。 */
    private void recordUsage(PostReasoningEvent event) {
        if (budget == null) {
            return;
        }
        Msg reasoning = event.getReasoningMessage();
        ChatUsage usage = reasoning == null ? null : reasoning.getChatUsage();
        if (usage != null) {
            budget.addUsage(usage.getInputTokens(), usage.getOutputTokens());
        }
    }

    private void record(PostActingEvent event) {
        ToolUseBlock use = event.getToolUse();
        long began = startedAt.getOrDefault(use.getId(), clock.millis());
        long duration = Math.max(0, clock.millis() - began);
        startedAt.remove(use.getId());

        // 状态看框架给的结果：只有失败的调用才取走预算的拒绝原因，成功的调用不会吞掉别人的错误码
        boolean failed = event.getToolResult() == null
            || event.getToolResult().getState() == ToolResultState.ERROR;
        if (!failed) {
            successfulTools.add(use.getName());
        }
        String rejection = failed && budget != null ? budget.pollRejection() : null;

        HeronAgentToolCall row = new HeronAgentToolCall();
        row.setId(IdUtil.getSnowflakeNextId());
        row.setResearchId(researchId);
        row.setAttemptNo(attemptNo);
        row.setRoundNo(rounds.get());
        row.setToolName(use.getName());
        row.setToolUseId(use.getId());
        row.setRequestSummary(truncate(redact(String.valueOf(use.getInput()))));
        row.setResponseSummary(truncate(redact(describe(event.getToolResult()))));
        row.setStatus(failed ? HeronAgentToolCall.STATUS_FAILED : HeronAgentToolCall.STATUS_OK);
        row.setErrorCode(rejection);
        row.setDurationMs(duration);
        LocalDateTime now = LocalDateTime.now(clock);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            mapper.insert(row);
        } catch (RuntimeException e) {
            // 账本失败不影响研究结论
            org.slf4j.LoggerFactory.getLogger(ToolCallLedger.class)
                .warn("工具调用记账失败 researchId={} tool={} cause={}", researchId, use.getName(), e.getMessage());
        }

    }

    private static String describe(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        result.getOutput().forEach(block -> text.append(block).append('\n'));
        return text.toString();
    }

    /**
     * 入出参是模型可以自由构造的，落库前把看起来像凭据的键值打掉。
     * 只有长度截断挡不住这类内容，而这张表会被长期回看。
     */
    private static String redact(String text) {
        return text == null ? null : SECRET.matcher(text).replaceAll("$1\"***\"");
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY);
    }

    public int rounds() {
        return rounds.get();
    }

    /** 本次 attempt 实际成功执行的工具名，用于校验 Agent 是否真的读取过证据。 */
    public Set<String> successfulTools() {
        return Set.copyOf(successfulTools);
    }
}
