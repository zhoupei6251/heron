package org.dromara.heron.agentscope;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 一次研究的预算：工具调用次数 + token 总量。
 *
 * <p>为什么不在 hook 里停：同一轮推理可能一次发出多个工具调用，{@code stopAgent()} 拦不住已经发出的那些，
 * 实测预算是 2 却实际执行了 5 次。在工具入口处拦才是真的把工作量关住。
 *
 * <p>超限不是抛异常终止研究，而是让该次工具调用失败：模型会看到「预算已用尽」，
 * 转而基于已有信息总结——这正是 plan.md §5.2 要的「保存已获取证据、输出部分结果」。
 *
 * <p>token 只能在事后算：模型调用的用量要等这一轮结束才知道。所以它在下一次工具入口生效，
 * 那时花费已经发生，能拦住的是之后的继续烧钱，这与工具次数的口径一致。
 */
public final class ResearchBudget {

    static final String TOOL_EXCEEDED = "tool_budget_exceeded";
    static final String TOKEN_EXCEEDED = "token_budget_exceeded";
    static final String CANCELLED = "research_cancelled";

    private final int toolLimit;
    /** 小于等于 0 表示不设 token 上限。 */
    private final long tokenLimit;
    /** 取消信号：由租约续命线程读库状态后置位；null 表示不可取消。 */
    private final BooleanSupplier cancelSignal;
    private final AtomicInteger toolUsed = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    /** 最近一次拒绝原因，供账本给那一行打错误码后取走。 */
    private volatile String lastRejection;
    private volatile boolean exceeded;

    public ResearchBudget(int toolLimit, long tokenLimit) {
        this(toolLimit, tokenLimit, null);
    }

    public ResearchBudget(int toolLimit, long tokenLimit, BooleanSupplier cancelSignal) {
        this.toolLimit = Math.max(1, toolLimit);
        this.tokenLimit = tokenLimit;
        this.cancelSignal = cancelSignal;
    }

    /** @return null 表示放行，否则是拒绝原因码 */
    public String tryAcquireTool() {
        // 取消排在最前：用户已经喊停，就不该再花钱查一条数据
        if (cancelSignal != null && cancelSignal.getAsBoolean()) {
            lastRejection = CANCELLED;
            return CANCELLED;
        }
        // token 先判：被 token 拒掉的调用不该再吃掉一个工具名额
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

    /** 任一项到顶：结论标 partial。 */
    public boolean exceeded() {
        return exceeded;
    }
}
