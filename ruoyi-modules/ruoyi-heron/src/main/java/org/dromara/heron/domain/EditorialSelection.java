package org.dromara.heron.domain;

/**
 * 线内编辑的最终选择：总编辑定了走哪条线，线内编辑定具体素材。
 * 这些值必须在认领时一次性定下来，之后生产只读冻结快照，不再重新选择。
 *
 * <p>线的必填项不同，故意不统一成同一组可空字段：
 * 数据看板线与趋势洞察线要 dataSourceKey 和 templateId；解说线要 candidateId 和至少一条证据。
 */
public record EditorialSelection(
    String line,
    Long candidateId,
    String dataSourceKey,
    boolean digest,
    String templateId,
    String storyAngle,
    java.util.List<String> sourceEvidenceIds,
    Long opportunityId,
    Opportunity opportunity
) {

    public static final String DATA_BOARD = "DATA_BOARD";
    public static final String NEWS_EXPLAINER = "NEWS_EXPLAINER";
    /** 趋势洞察线：与数据看板共用取数口径，但结论由 trend/1 的机器判定驱动。 */
    public static final String TREND_INSIGHT = "TREND_INSIGHT";

    /**
     * 选题上下文：机会自己的头线与信号，以及当时的取数口径。
     *
     * <p>认领时一次定完，冻结后生产只读这一份：机会表会在下一次分析时被更新，
     * 冻结之后再回查，拿到的就可能是另一轮分析的结论了。
     *
     * @param opportunityId 机会 id
     * @param sourceKey 机会绑定的数据源
     * @param headline 机会的头线（选题当时的判断）
     * @param signals 机会的信号 JSON 原文
     * @param windowPoints 选题当时的取数点数
     * @param expectedDataSha256 选题当时的快照哈希
     * @param shape 形态
     * @param cadence 节奏
     * @param metricKey 指标 key
     */
    public record Opportunity(Long opportunityId, String sourceKey, String headline, String signals,
                              int windowPoints, String expectedDataSha256,
                              String shape, String cadence, String metricKey) { }

    /** 老入口：不是从数据机会来的（解说线、以及数据看板的旧选源路径）。 */
    public EditorialSelection(String line, Long candidateId, String dataSourceKey, boolean digest,
                              String templateId, String storyAngle, java.util.List<String> sourceEvidenceIds) {
        this(line, candidateId, dataSourceKey, digest, templateId, storyAngle, sourceEvidenceIds, null, null);
    }

    /** 从数据机会来的选择：还没有带取数口径的老调用。 */
    public EditorialSelection(String line, Long candidateId, String dataSourceKey, boolean digest,
                              String templateId, String storyAngle, java.util.List<String> sourceEvidenceIds,
                              Long opportunityId) {
        this(line, candidateId, dataSourceKey, digest, templateId, storyAngle, sourceEvidenceIds, opportunityId, null);
    }

    public boolean dataBoard() {
        return DATA_BOARD.equals(line);
    }

    /** 趋势洞察线也走数据源取数，但稿子与闸集不同。 */
    public boolean trendInsight() {
        return TREND_INSIGHT.equals(line);
    }

    /** 两条数据线（看板 / 趋势）共用取数、占位符与模板变量路径。 */
    public boolean datasetLine() {
        return dataBoard() || trendInsight();
    }

    /** 校验放在紧凑构造器里：两条线的必填规则不同，不能靠一组可空字段表达。 */
    public EditorialSelection {
        if (!DATA_BOARD.equals(line) && !TREND_INSIGHT.equals(line) && !NEWS_EXPLAINER.equals(line)) {
            throw new IllegalStateException("线内编辑给出了未知生产线：" + line);
        }
        if (templateId == null || !templateId.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalStateException("线内编辑没有给出合法模板：" + templateId);
        }
        if (DATA_BOARD.equals(line) || TREND_INSIGHT.equals(line)) {
            if (dataSourceKey == null || dataSourceKey.isBlank()) {
                throw new IllegalStateException("数据线缺少数据源");
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
