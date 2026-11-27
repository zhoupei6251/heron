package org.dromara.heron.agentscope.agent;

import org.dromara.heron.agentscope.research.ResearchBudget;
import org.dromara.heron.agentscope.research.ResearchVerification;
import org.dromara.heron.agentscope.research.ToolCallLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核验机制的存在理由就是识破假结论。
 *
 * <p>这条路径在真实链路里很难自然出现（核验员多数时候同意），所以**主动喂一条与数据
 * 方向相反的结论**去压它——不然「能识别冲突」这句话永远只是声明。
 *
 * <p>需要真模型，标记为 render 组，不进默认测试集。
 */
@Tag("render")
@DisplayName("核验员：伪造结论必须被识破")
class VerifierDisagreeRealTest {

    @Test
    @DisplayName("结论与数据方向相反时判 DISAGREE，并列出具体冲突")
    void detectsFabricatedClaim() {
        var env = VerifierProbeSupport.prepare();
        ResearchVerification verification = new ResearchVerification();
        ResearchBudget budget = new ResearchBudget(12, 300_000, Duration.ofMinutes(5),
            java.time.Clock.systemDefaultZone(), null);

        try (var agent = env.factory().createVerifier("real-disagree", budget,
            new ToolCallLedger(1L, 1001, env.ledgerMapper(), java.time.Clock.systemDefaultZone(), budget),
            verification)) {

            String goal = "机会 900 在候选里。判断它值不值得做成一条 60 秒的数据视频。";
            // 伪造：实际 -2.39% 小幅下行，这里说成暴涨 15.3% 且创 30 年新高
            String claim = "判断：PRODUCE\n"
                + "理由：澳元兑人民币近 7 日暴涨 15.3%，从 0.4712 升到 0.5420，"
                + "创下该货币对 30 年历史新高，是极强的单边升值信号。\n"
                + "依据的快照标识：deadbeef";

            agent.call("研究目标：" + goal + "\n\n被核验的结论：\n" + claim)
                .block(Duration.ofMinutes(6));
        }

        assertEquals(ResearchVerification.DISAGREE, verification.verdict(),
            "与数据方向相反的结论必须判 DISAGREE，实际：" + verification.verdict());
        assertFalse(verification.conflicts().isEmpty(), "判 DISAGREE 应当列出具体冲突");
        assertTrue(verification.conflicted());
    }

    @Test
    @DisplayName("结论与数据一致时判 AGREE")
    void acceptsConsistentClaim() {
        var env = VerifierProbeSupport.prepare();
        ResearchVerification verification = new ResearchVerification();
        ResearchBudget budget = new ResearchBudget(12, 300_000, Duration.ofMinutes(5),
            java.time.Clock.systemDefaultZone(), null);

        try (var agent = env.factory().createVerifier("real-agree", budget,
            new ToolCallLedger(1L, 1002, env.ledgerMapper(), java.time.Clock.systemDefaultZone(), budget),
            verification)) {
            String claim = "判断：REJECT\n理由：近 7 日 -2.39%，只有 8 个点，无独立基线，无法验证极端性。";
            agent.call("研究目标：机会 900 值不值得做成视频。\n\n被核验的结论：\n" + claim)
                .block(Duration.ofMinutes(6));
        }

        assertEquals(ResearchVerification.AGREE, verification.verdict(),
            "与数据一致的结论不该被判冲突，实际：" + verification.verdict());
    }
}
