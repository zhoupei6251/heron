package org.dromara.heron.agentscope;

import java.util.ArrayList;
import java.util.List;

/**
 * 核验员交的判定。
 *
 * <p>为什么要有独立的判定而不是复用 {@link ResearchFindings}：
 * 「这个判断值不值得做」和「这个判断站不站得住」是两个问题。混成一个字段之后，
 * 下游没法区分「研究员判了 REJECT」和「核验员认为研究员的 REJECT 是错的」。
 */
public final class ResearchVerification {

    /** 独立复核后确认结论站得住。 */
    public static final String AGREE = "AGREE";
    /** 数据和结论矛盾，或结论超出证据。 */
    public static final String DISAGREE = "DISAGREE";
    /** 数据不足以判断。 */
    public static final String UNCERTAIN = "UNCERTAIN";

    private String verdict;
    private String reason;
    private final List<String> conflicts = new ArrayList<>();

    void record(String verdict, String reason, List<String> conflicts) {
        this.verdict = verdict == null ? null : verdict.trim().toUpperCase();
        this.reason = reason;
        this.conflicts.clear();
        if (conflicts != null) {
            for (String item : conflicts) {
                String trimmed = item == null ? "" : item.trim();
                if (!trimmed.isEmpty() && !this.conflicts.contains(trimmed)) {
                    this.conflicts.add(trimmed);
                }
            }
        }
    }

    public boolean submitted() {
        return verdict != null;
    }

    /** 结论与原始证据是否冲突：只有 DISAGREE 算冲突。 */
    public boolean conflicted() {
        return DISAGREE.equals(verdict);
    }

    public String verdict() {
        return verdict;
    }

    public String reason() {
        return reason;
    }

    public List<String> conflicts() {
        return List.copyOf(conflicts);
    }
}
