package org.dromara.heron.service.impl;

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

    public enum Gate {
        PASS,
        BLOCK
    }

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

    public record GateResult(Gate gate, String blockReason) {
    }
}
