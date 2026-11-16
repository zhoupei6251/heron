package org.dromara.heron.agentscope;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核验的两个边界：判定只能是三选一，且「冲突」只有 DISAGREE 才算。
 * UNCERTAIN 说的是「数据不够」，不是「结论错了」——混为一谈会让下游误以为发现了问题。
 */
@DisplayName("核验：判定归一化与冲突语义")
class ResearchVerificationToolTest {

    @Test
    @DisplayName("judgement 归一化，冲突项去重后保留")
    void recordsVerdict() {
        ResearchVerification verification = new ResearchVerification();

        String reply = new ResearchVerificationTool(verification)
            .submitVerification("disagree", "结论引用的点数和数据对不上",
                List.of(" 只有 8 个点却说 30 期新低 ", "只有 8 个点却说 30 期新低", " "));

        assertEquals(ResearchVerification.DISAGREE, verification.verdict());
        assertEquals(1, verification.conflicts().size(), "重复与空白项要去掉");
        assertTrue(verification.conflicted());
        assertTrue(reply.contains("DISAGREE"));
    }

    @Test
    @DisplayName("UNCERTAIN 不是冲突：数据不够不等于结论错了")
    void uncertainIsNotConflict() {
        ResearchVerification verification = new ResearchVerification();

        new ResearchVerificationTool(verification)
            .submitVerification("UNCERTAIN", "源取不到，无法判断", null);

        assertTrue(verification.submitted());
        assertFalse(verification.conflicted());
        assertEquals(0, verification.conflicts().size());
    }

    @Test
    @DisplayName("非法判定与空理由都被拒绝")
    void validatesInput() {
        var tool = new ResearchVerificationTool(new ResearchVerification());

        assertThrows(HeronBusinessException.class, () -> tool.submitVerification("MAYBE", "理由", null));
        assertThrows(HeronBusinessException.class, () -> tool.submitVerification("AGREE", "  ", null));
        assertFalse(tool.submitVerification("AGREE", "复核后确认一致", null).isEmpty());
    }

    @Test
    @DisplayName("没交判定时 submitted=false，不会伪装成已核验")
    void unsubmitted() {
        assertFalse(new ResearchVerification().submitted());
    }
}
