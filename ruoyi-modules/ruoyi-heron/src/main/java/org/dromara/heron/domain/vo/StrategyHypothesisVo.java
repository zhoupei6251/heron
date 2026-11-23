package org.dromara.heron.domain.vo;

/**
 * 一条模型策略假设。
 *
 * <p>字段里的 {@code minSamples / minWeeks / metric / falsifyIf} 就是「怎么验证」，
 * 缺一项的假设不该出现在这个视图里——它只是猜测。
 *
 * @param id          假设 id
 * @param claim       一句策略判断
 * @param dimension   归因维度
 * @param bucketValue 分组值
 * @param metric      验证看哪个指标
 * @param minSamples  验证所需最少样本数
 * @param minWeeks    验证所需最少自然周数
 * @param falsifyIf   出现什么结果算被证伪
 * @param basis       生成时的样本依据
 * @param source      模型与提示词版本
 * @param generatedAt 生成时间
 */
public record StrategyHypothesisVo(String id, String claim, String dimension, String bucketValue,
                                   String metric, int minSamples, int minWeeks, String falsifyIf,
                                   String basis, String source, String generatedAt) {
}
