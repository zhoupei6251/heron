package org.dromara.heron.service;

/**
 * 把「已采纳的研究决策」转成可认领的候选选题。
 *
 * <p>为什么产出候选而不是直接建生产任务：plan.md §16 要求 {@code PRODUCE} 进候选池
 * 「作为候选之一，**不取代**总编辑」。候选池下游是认领 → 生产，跳过它等于让 Agent 绕过了选题。
 *
 * <p>为什么这里要求的输入是「已采纳的决策」而不是 Agent 的原始判断：
 * §6.3 第 5 条禁止 Agent 直接改候选池。人的显式采纳就是那道闸。
 */
public interface IResearchPromotionService {

    /**
     * 把一个已采纳的决策提升为选题候选。
     *
     * <p>幂等：同一决策重复提升返回同一个候选，不重复建。
     *
     * @param decisionId 内容决策 id
     * @return 候选 id
     */
    long promote(long decisionId);
}
