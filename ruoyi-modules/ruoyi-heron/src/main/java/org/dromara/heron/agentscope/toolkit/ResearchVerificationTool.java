package org.dromara.heron.agentscope.toolkit;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.heron.agentscope.research.ResearchBudget;
import org.dromara.heron.agentscope.research.ResearchVerification;
import org.dromara.heron.domain.HeronBusinessException;

import java.util.List;

/**
 * 核验员的收尾工具。
 *
 * <p>为什么单独一个类：框架按<b>类自身声明</b>的方法注册工具，所以把收尾工具挪出来，
 * 核验员就注册「查询工具 + 这一个」，研究员注册「查询工具 + submit_findings」，
 * 两边的收尾动作不会串。
 *
 * <p>和 {@code submit_findings} 一样用 {@code returnDirect}：判定一出就该结束，不该再花一轮。
 */
public final class ResearchVerificationTool {

    private final ResearchVerification verification;

    public ResearchVerificationTool(ResearchVerification verification) {
        this.verification = verification;
    }

    /**
     * 记录核验判定并结束本轮。
     *
     * @param verdict   AGREE / DISAGREE / UNCERTAIN，大小写不敏感
     * @param reason    判定理由，不能为空
     * @param conflicts 与原始数据对不上的地方，可为 null
     * @return 确认文本
     * @throws HeronBusinessException verdict 不在三值内或 reason 为空时抛 400
     */
    @Tool(name = "submit_verification",
        description = "核验到此为止：交出你的判定。看完原始数据、或者确认数据不足以判断时调用它",
        readOnly = true,
        returnDirect = true)
    public String submitVerification(
        @ToolParam(name = "verdict", description = "AGREE 站得住 / DISAGREE 与数据矛盾 / UNCERTAIN 数据不足以判断", required = true) String verdict,
        @ToolParam(name = "reason", description = "为什么，要指向具体数字或具体缺口", required = true) String reason,
        @ToolParam(name = "conflicts", description = "结论和原始数据对不上的地方，每条一句；没有就留空", required = false) List<String> conflicts) {

        String normalized = verdict == null ? "" : verdict.trim().toUpperCase();
        if (!ResearchVerification.AGREE.equals(normalized)
            && !ResearchVerification.DISAGREE.equals(normalized)
            && !ResearchVerification.UNCERTAIN.equals(normalized)) {
            throw new HeronBusinessException(400, "invalid_argument",
                "verdict 只能是 AGREE / DISAGREE / UNCERTAIN，收到 " + verdict);
        }
        if (reason == null || reason.isBlank()) {
            throw new HeronBusinessException(400, "invalid_argument", "reason 不能为空");
        }
        verification.record(normalized, reason.trim(), conflicts);
        return "核验已记录：" + normalized + "。" + reason.trim();
    }
}
