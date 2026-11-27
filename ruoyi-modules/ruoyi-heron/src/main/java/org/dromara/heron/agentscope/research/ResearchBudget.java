package org.dromara.heron.agentscope.research;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 一次研究的预算：工具次数 + token 总量 + 墙钟时长。
 *
 * <p>为什么不在 hook 里停：同一轮推理可能一次发出多个工具调用，{@code stopAgent()} 拦不住已经发出的那些，
 * 实测预算是 2 却实际执行了 5 次。在工具入口处拦才是真的把工作量关住。
 *
 * <p>超限不是抛异常终止研究，而是让该次工具调用失败：模型会看到「预算已用尽」，
 * 转而基于已有信息总结——这正是 plan.md §5.2 要的「保存已获取证据、输出部分结果」。
 *
 * <p>token 与时长只能事后算，所以它们在下一次工具入口生效：花费已经发生，
 * 能拦住的是之后的继续烧钱。这与工具次数的口径一致。
 */
public final class ResearchBudget {

    public static final String TOOL_EXCEEDED = "tool_budget_exceeded";
    public static final String TOKEN_EXCEEDED = "token_budget_exceeded";
    public static final String TIME_EXCEEDED = "time_budget_exceeded";
    public static final String CANCELLED = "research_cancelled";

    private final int toolLimit;
    /** 小于等于 0 表示不设 token 上限。 */
    private final long tokenLimit;
    /** 单次调查最长时长（plan.md §5.2 初始值 5 分钟）。 */
    private final Duration maxDuration;
    private final Clock clock;
    private final Instant deadline;
    /** 取消信号：由租约续命线程读库状态后置位；null 表示不可取消。 */
    private final BooleanSupplier cancelSignal;
    private final AtomicInteger toolUsed = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    /** 最近一次拒绝原因，供账本给那一行打错误码后取走。 */
    private volatile String lastRejection;
    private volatile boolean exceeded;

    /**
     * 默认预算：5 分钟、系统时钟、不可取消。
     *
     * @param toolLimit  工具次数上限，小于 1 时按 1 处理
     * @param tokenLimit token 上限，小于等于 0 表示不设限
     */
    public ResearchBudget(int toolLimit, long tokenLimit) {
        this(toolLimit, tokenLimit, Duration.ofMinutes(5), Clock.systemDefaultZone(), null);
    }

    /**
     * @param toolLimit    工具次数上限，小于 1 时按 1 处理
     * @param tokenLimit   token 上限，小于等于 0 表示不设限
     * @param maxDuration  单次调查最长时长，null 时取 5 分钟
     * @param clock        用于判定截止时间，null 时取系统默认时区时钟
     * @param cancelSignal 取消信号，null 表示不可取消
     */
    public ResearchBudget(int toolLimit, long tokenLimit, Duration maxDuration, Clock clock,
                          BooleanSupplier cancelSignal) {
        this.toolLimit = Math.max(1, toolLimit);
        this.tokenLimit = tokenLimit;
        this.maxDuration = maxDuration == null ? Duration.ofMinutes(5) : maxDuration;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.deadline = this.clock.instant().plus(this.maxDuration);
        this.cancelSignal = cancelSignal;
    }

    /** @return null 表示放行，否则是拒绝原因码 */
    public String tryAcquireTool() {
        // 取消排在最前：用户已经喊停，就不该再花钱查一条数据
        if (cancelSignal != null && cancelSignal.getAsBoolean()) {
            lastRejection = CANCELLED;
            return CANCELLED;
        }
        // 时长在次数之前：跑到点就该收尾，不该先用完次数才想起来超时
        if (!clock.instant().isBefore(deadline)) {
            lastRejection = TIME_EXCEEDED;
            exceeded = true;
            return TIME_EXCEEDED;
        }
        // token 先于工具计数：被 token 拒掉的调用不该再吃掉一个工具名额
        if (tokenLimit > 0 && totalTokens() >= tokenLimit) {
            lastRejection = TOKEN_EXCEEDED;
            exceeded = true;
            return TOKEN_EXCEEDED;
        }
        if (toolUsed.incrementAndGet() > toolLimit) {
            lastRejection = TOOL_EXCEEDED;
            exceeded = true;
            return TOOL_EXCEEDED;
        }
        return null;
    }

    /** 取走并清空最近一次拒绝原因；调用方只在本次调用确实失败时取。 */
    String pollRejection() {
        String reason = lastRejection;
        lastRejection = null;
        return reason;
    }

    /** 累加供应商报告的 token 用量；非正值忽略。 */
    public void addUsage(int input, int output) {
        if (input > 0) {
            inputTokens.addAndGet(input);
        }
        if (output > 0) {
            outputTokens.addAndGet(output);
        }
    }

    /** 实际执行的次数（不含被拒的）。 */
    public int toolUsed() {
        return Math.min(toolUsed.get(), toolLimit);
    }

    public int toolLimit() {
        return toolLimit;
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long outputTokens() {
        return outputTokens.get();
    }

    public long totalTokens() {
        return inputTokens.get() + outputTokens.get();
    }

    public long tokenLimit() {
        return tokenLimit;
    }

    public Duration maxDuration() {
        return maxDuration;
    }

    /** 任一项到顶：结论标 partial。 */
    public boolean exceeded() {
        return exceeded;
    }
}
