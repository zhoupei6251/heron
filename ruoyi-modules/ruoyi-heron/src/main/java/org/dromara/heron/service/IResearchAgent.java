package org.dromara.heron.service;

import java.math.BigDecimal;
import java.util.function.BooleanSupplier;

/**
 * 研究执行端口。业务只依赖这个接口，不认识具体用的是哪个 Agent 框架。
 *
 * <p>实现放在 {@code org.dromara.heron.agentscope}：只有那个包会 import 框架的类型，
 * 换框架时改动面收敛在一处，业务代码不动。
 */
public interface IResearchAgent {

    /**
     * 在指定会话里跑一次研究。
     */
    Outcome research(Request request);

    /**
     * 一次研究的输入。
     *
     * <p>用记录而不是位置参数：这里已经连着两个 long 和一个 int，再多一个 order 错了编译器不会报。
     *
     * @param sessionId     研究级会话标识；同一个研究重试要传同一个值，才能续上上下文
     * @param researchId    研究任务 id，用于工具调用记账
     * @param opportunityId 研究对象的机会 id，用于绑定它依据的那份快照
     * @param attemptNo     第几次执行，用于区分重试
     * @param goal          研究目标，人读得懂的自然语言
     * @param cancelled     取消信号；为 true 时后续工具调用一律被拒，研究收尾
     */
    record Request(String sessionId, long researchId, long opportunityId, int attemptNo,
                   String goal, BooleanSupplier cancelled) {
    }

    /**
     * 一次研究的结果。
     *
     * @param text            模型给出的结论文本，不会是 null
     * @param rounds          实际推理轮数
     * @param toolCalls       实际工具调用次数
     * @param inputTokens     供应商报告的输入 token，未报告时为 0
     * @param outputTokens    供应商报告的输出 token，未报告时为 0
     * @param budgetExceeded  是否因预算到点被强制收尾（此时结论标 partial）
     * @param maxItersReached 是否撞上轮数上限（同样是「不完整但可用」，标 partial）
     * @param findings        模型自己交的结论；没交时各字段为 null/false
     */
    record Outcome(String text, int rounds, int toolCalls,
                   long inputTokens, long outputTokens,
                   boolean budgetExceeded, boolean maxItersReached, Findings findings) {
    }

    /**
     * 模型主动交的结论，以及从账本反查出来的证据链。
     *
     * @param decision      PRODUCE / OBSERVE / REJECT，没交时为 null
     * @param confidence    0 到 1，没给时为 null
     * @param stoppedEarly  是否由模型自己判断「证据够了」而停下，而不是被闸打停
     * @param evidenceJson  证据链 JSON，见 {@code EvidenceCollector}
     * @param reportJson    结构化研究报告，见 {@code ResearchReport}；含结论、要点、风险与快照绑定
     */
    record Findings(String decision, BigDecimal confidence, boolean stoppedEarly,
                    String evidenceJson, String reportJson) {
    }
}
