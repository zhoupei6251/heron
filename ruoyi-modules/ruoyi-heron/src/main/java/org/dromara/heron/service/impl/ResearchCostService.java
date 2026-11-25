package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.vo.ResearchCostVo;
import org.dromara.heron.mapper.HeronContentDecisionMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本与质量概览。
 *
 * <p>为什么要有这个：plan.md §12.1 要求能算「单个选题研究成本」。没有这个数，
 * 分级阈值（55/70）就只能拍脑袋，永远校不准。
 *
 * <p>为什么把等级、决策类型分开汇总：总量看不出来「钱花在哪些机会上」。
 * L1 和 L3 的单次成本差一个数量级，混在一起的平均值没有意义。
 *
 * <p>金额在读侧按当前单价折算（{@link ModelPrice}），不写进账本：历史记录跟着单价走，
 * 免得为「冻结当时单价」多一次迁移。单价没配时金额是 0，同时把 {@code priced=false} 交出去，
 * 让界面能说「这个数不可信」而不是假装花了 0 元。
 */
@Service
@RequiredArgsConstructor
public class ResearchCostService {

    private final HeronResearchTaskMapper researchTaskMapper;
    private final HeronContentDecisionMapper decisionMapper;
    private final ModelPriceConfig prices;
    private final ObjectProvider<Clock> clocks;

    /**
     * @param days 统计窗口天数；0 或负数按 30 天
     */
    public ResearchCostVo overview(int days) {
        int window = days <= 0 ? 30 : Math.min(days, 365);
        LocalDateTime since = now().minusDays(window);

        List<HeronResearchTask> tasks = researchTaskMapper.selectList(QueryBuilder
            .lambda(HeronResearchTask.class)
            .ge(HeronResearchTask::getCreateTime, since)
            .build());

        ResearchCostVo vo = new ResearchCostVo();
        vo.setDays(window);
        vo.setResearchCount(tasks.size());

        long input = 0;
        long output = 0;
        long toolCalls = 0;
        long decided = 0;
        Map<String, long[]> gradeAgg = new LinkedHashMap<>();
        for (HeronResearchTask task : tasks) {
            long in = task.getInputTokens() == null ? 0 : task.getInputTokens();
            long out = task.getOutputTokens() == null ? 0 : task.getOutputTokens();
            input += in;
            output += out;
            toolCalls += task.getToolCalls() == null ? 0 : task.getToolCalls();
            if (task.getDecisionType() != null) {
                decided++;
            }
            String grade = task.getGrade() == null ? "L2" : task.getGrade();
            long[] agg = gradeAgg.computeIfAbsent(grade, key -> new long[3]);
            agg[0]++;
            agg[1] += in + out;
            if (HeronResearchTask.STATUS_SUCCEEDED.equals(task.getStatus())
                || HeronResearchTask.STATUS_PARTIAL.equals(task.getStatus())) {
                agg[2]++;
            }
        }
        vo.setInputTokens(input);
        vo.setOutputTokens(output);
        vo.setToolCalls(toolCalls);
        vo.setDecidedCount(decided);
        vo.setAvgTokens(tasks.isEmpty() ? 0 : (input + output) / tasks.size());
        // 金额与 token 用同一批数算出来：两处各算一遍迟早对不上
        ModelPrice price = prices.price();
        vo.setAmount(price.amount(input, output));
        vo.setPriced(price.priced());
        vo.setByGrade(gradeCosts(gradeAgg));

        List<HeronContentDecision> decisions = decisionMapper.selectList(QueryBuilder
            .lambda(HeronContentDecision.class)
            .ge(HeronContentDecision::getCreateTime, since)
            .build());
        vo.setByDecision(decisionCounts(decisions));
        long accepted = decisions.stream()
            .filter(d -> HeronContentDecision.STATUS_ACCEPTED.equals(d.getStatus())).count();
        vo.setAcceptedCount(accepted);
        vo.setVerifiedCount(decisions.stream().filter(d -> d.getVerificationVerdict() != null).count());
        vo.setConflictCount(decisions.stream()
            .filter(d -> "DISAGREE".equals(d.getVerificationVerdict())).count());
        if (!decisions.isEmpty()) {
            vo.setAcceptanceRate(BigDecimal.valueOf(accepted)
                .divide(BigDecimal.valueOf(decisions.size()), 4, RoundingMode.HALF_UP));
        }
        return vo;
    }

    private static List<ResearchCostVo.GradeCost> gradeCosts(Map<String, long[]> agg) {
        List<ResearchCostVo.GradeCost> rows = new ArrayList<>();
        agg.forEach((grade, values) -> rows.add(new ResearchCostVo.GradeCost(
            grade, values[0], values[0] == 0 ? 0 : values[1] / values[0], values[2])));
        rows.sort((a, b) -> a.grade().compareTo(b.grade()));
        return rows;
    }

    private static List<ResearchCostVo.DecisionCount> decisionCounts(List<HeronContentDecision> decisions) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (HeronContentDecision decision : decisions) {
            String type = decision.getDecisionType() == null ? "UNKNOWN" : decision.getDecisionType();
            long[] values = agg.computeIfAbsent(type, key -> new long[2]);
            values[0]++;
            if (HeronContentDecision.STATUS_ACCEPTED.equals(decision.getStatus())) {
                values[1]++;
            }
        }
        List<ResearchCostVo.DecisionCount> rows = new ArrayList<>();
        agg.forEach((type, values) -> rows.add(
            new ResearchCostVo.DecisionCount(type, values[0], values[1])));
        return rows;
    }

    private LocalDateTime now() {
        Clock clock = clocks.getIfAvailable();
        return LocalDateTime.now(clock == null ? Clock.systemDefaultZone() : clock);
    }
}
