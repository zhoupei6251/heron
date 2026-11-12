package org.dromara.heron.domain;

/**
 * 线内编辑的最终选择 + 它相对总编辑原判的偏差。
 *
 * <p>为什么不把 originalLine/fallbackApplied 直接塞进 {@link EditorialSelection}：
 * 那个 record 的紧凑构造器承载两条线各自的必填校验，加可空字段会把「必填」和「溯源」搅在一起，
 * 而且它会被原样写进生产快照，改形状就得升快照 schema。这里单独包一层，快照保持不动。
 *
 * @param selection       最终选择，生产链路只消费这个
 * @param originalLine    总编辑原始决策线（DATA_BOARD / NEWS_EXPLAINER）
 * @param fallbackApplied 是否发生了跨线回退
 * @param fallbackReason  回退原因码：data_board_empty / explainer_empty
 */
public record EditorialChoice(EditorialSelection selection, String originalLine,
                              boolean fallbackApplied, String fallbackReason) {

    public EditorialChoice {
        if (selection == null) {
            throw new IllegalStateException("线内编辑没有给出最终选择");
        }
        if (originalLine == null || originalLine.isBlank()) {
            throw new IllegalStateException("缺少总编辑原始决策线");
        }
        if (!fallbackApplied && fallbackReason != null) {
            throw new IllegalStateException("没有回退却带了回退原因：" + fallbackReason);
        }
        if (fallbackApplied && (fallbackReason == null || fallbackReason.isBlank())) {
            throw new IllegalStateException("发生了回退但没有原因");
        }
    }

    /** 最终执行线：落库与后续分析都以它为准。 */
    public String finalLine() {
        return selection.line();
    }
}
