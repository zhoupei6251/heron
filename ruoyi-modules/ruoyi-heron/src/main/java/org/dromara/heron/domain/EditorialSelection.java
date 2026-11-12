package org.dromara.heron.domain;

/**
 * 线内编辑的最终选择：总编辑定了走哪条线，线内编辑定具体素材。
 * 这些值必须在认领时一次性定下来，之后生产只读冻结快照，不再重新选择。
 *
 * <p>两条线的必填项不同，故意不统一成同一组可空字段：
 * 数据看板线要 dataSourceKey 和 templateId；解说线要 candidateId 和至少一条证据。
 */
public record EditorialSelection(
    String line,
    Long candidateId,
    String dataSourceKey,
    boolean digest,
    String templateId,
    String storyAngle,
    java.util.List<String> sourceEvidenceIds
) {

    public static final String DATA_BOARD = "DATA_BOARD";
    public static final String NEWS_EXPLAINER = "NEWS_EXPLAINER";

    public boolean dataBoard() {
        return DATA_BOARD.equals(line);
    }

    /** 校验放在紧凑构造器里：两条线的必填规则不同，不能靠一组可空字段表达。 */
    public EditorialSelection {
        if (!DATA_BOARD.equals(line) && !NEWS_EXPLAINER.equals(line)) {
            throw new IllegalStateException("线内编辑给出了未知生产线：" + line);
        }
        if (templateId == null || !templateId.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalStateException("线内编辑没有给出合法模板：" + templateId);
        }
        if (DATA_BOARD.equals(line)) {
            if (dataSourceKey == null || dataSourceKey.isBlank()) {
                throw new IllegalStateException("数据看板线缺少数据源");
            }
        } else {
            if (candidateId == null) {
                throw new IllegalStateException("解说线缺少候选");
            }
            if (sourceEvidenceIds == null || sourceEvidenceIds.isEmpty()) {
                throw new IllegalStateException("解说线缺少证据");
            }
        }
        sourceEvidenceIds = sourceEvidenceIds == null ? java.util.List.of() : java.util.List.copyOf(sourceEvidenceIds);
    }
}
