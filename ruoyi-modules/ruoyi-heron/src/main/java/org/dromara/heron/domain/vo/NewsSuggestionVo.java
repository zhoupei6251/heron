package org.dromara.heron.domain.vo;

/**
 * 创作建议：来自 L3 质量报告的 subjective / hypothesis 条目。
 *
 * <p>它不是结论，也不是预测——采纳与否由人决定，采纳后还要显式记一条策略覆盖。
 * 带上 {@code criterionClass} 与 {@code confidence} 是硬要求：主观条目一旦只显示文字，
 * 读者会把它当成跟客观判据一样的证据。
 *
 * @param id             版本号 + 判据号的稳定标识
 * @param versionId      出自哪个版本
 * @param criterionId    L3 判据号（如 pacing_variation）
 * @param text           报告里写的那句判断
 * @param source         来源标签：L3 质量审稿 · 提示词版本 · 模型
 * @param hypothesis     是否属于模型推测（class=hypothesis）
 * @param criterionClass objective / subjective / hypothesis
 * @param confidence     该条的置信度
 */
public record NewsSuggestionVo(String id, Long versionId, String criterionId, String text,
                               String source, boolean hypothesis, String criterionClass,
                               String confidence) {
}
