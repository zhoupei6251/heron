package org.dromara.heron.service.impl.shared.research;

/** 内容机会的统一评分视图；分数是派生值，不写回候选池或机会表。 */
public record OpportunityScore(
    String refKey,
    String sourceType,
    String label,
    int total,
    Dimensions dimensions,
    Gate gate,
    String blockReason
) {

    public OpportunityScore {
        if (total < 0 || total > 100) {
            throw new IllegalArgumentException("总分必须在 0–100：" + total);
        }
    }

    /** 硬门禁结论：PASS 可进入后续选线，BLOCK 直接拒绝。 */
    public enum Gate {
        PASS,
        BLOCK
    }

    /** 六维评分明细，每维都必须是 0–100。 */
    public record Dimensions(int evidence, int novelty, int timeliness,
                             int audience, int tension, int visual) {

        public Dimensions {
            requireRange("evidence", evidence);
            requireRange("novelty", novelty);
            requireRange("timeliness", timeliness);
            requireRange("audience", audience);
            requireRange("tension", tension);
            requireRange("visual", visual);
        }

        private static void requireRange(String name, int value) {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException(name + " 必须在 0–100：" + value);
            }
        }
    }

    /** 门禁结论与拒绝原因；BLOCK 时 blockReason 说明是哪条门禁拒绝的。 */
    public record GateResult(Gate gate, String blockReason) {
    }
}
