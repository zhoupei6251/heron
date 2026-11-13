package org.dromara.heron.agentscope;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究层的只读工具面：Agent 只能通过这里看数据，没有任何写入口。
 *
 * <p>返回类型只用 String / int / boolean / List，不用 {@code tools.jackson} 的 JsonNode——
 * AgentScope 用 Jackson 2 序列化工具入出参，本项目的 JsonNode 是 Jackson 3，直接传会踩两栈混用。
 *
 * <p>取值都设上限：一次查询的返回会进模型上下文，不设上限等于让单个工具吃掉预算。
 */
public class HeronResearchTools {

    /** 序列点数上限。 */
    static final int MAX_POINTS = 60;
    /** 默认点数：模型不传时用它。 */
    static final int DEFAULT_POINTS = 30;
    /** 单次工具返回的字符上限。 */
    static final int MAX_RESULT_CHARS = 4000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DatasetFetchService datasets;
    private final HeronDataOpportunityMapper opportunities;
    /** 每次研究一份；为 null 表示不限（单测与冒烟用）。 */
    private final ResearchBudget budget;

    public HeronResearchTools(DatasetFetchService datasets, HeronDataOpportunityMapper opportunities) {
        this(datasets, opportunities, null);
    }

    public HeronResearchTools(DatasetFetchService datasets, HeronDataOpportunityMapper opportunities,
                              ResearchBudget budget) {
        this.datasets = datasets;
        this.opportunities = opportunities;
        this.budget = budget;
    }

    /** 同协作者、换一份预算的副本：每次研究一个预算，不共享。 */
    public HeronResearchTools withBudget(ResearchBudget budget) {
        return new HeronResearchTools(datasets, opportunities, budget);
    }

    /**
     * 预算到点让本次调用失败，而不是终止研究：模型看到「已用尽」会转去总结已有信息。
     * 实测在 hook 里 stopAgent 挡不住同轮并行调用（预算 2 却执行了 5 次），必须在工具入口拦。
     */
    private void acquireBudget() {
        if (budget == null) {
            return;
        }
        String rejection = budget.tryAcquireTool();
        if (ResearchBudget.CANCELLED.equals(rejection)) {
            throw new HeronBusinessException(409, ResearchBudget.CANCELLED,
                "这次研究已被取消，请立即基于已获得的信息给出结论");
        }
        if (ResearchBudget.TOOL_EXCEEDED.equals(rejection)) {
            throw new HeronBusinessException(429, ResearchBudget.TOOL_EXCEEDED,
                "工具调用次数已用尽（上限 " + budget.toolLimit() + " 次），请基于已获得的信息给出结论");
        }
        if (ResearchBudget.TOKEN_EXCEEDED.equals(rejection)) {
            throw new HeronBusinessException(429, ResearchBudget.TOKEN_EXCEEDED,
                "本次研究的 token 预算已用尽（已用 " + budget.totalTokens()
                    + "，上限 " + budget.tokenLimit() + "），请基于已获得的信息给出结论");
        }
    }

    @Tool(name = "query_opportunity_detail",
        description = "按 id 查一条数据机会：来源、实体、指标、评分与信号明细",
        readOnly = true)
    public Map<String, Object> queryOpportunityDetail(
        @ToolParam(name = "opportunityId", description = "机会 id", required = true) long opportunityId) {

        acquireBudget();
        HeronDataOpportunity row = opportunities.selectById(opportunityId);
        if (row == null) {
            return Map.of("found", false, "opportunityId", opportunityId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("opportunityId", row.getOpportunityId());
        result.put("headline", row.getHeadline());
        result.put("sourceKey", row.getSourceKey());
        result.put("entity", row.getEntity());
        result.put("metric", row.getMetric());
        result.put("shape", row.getShape());
        result.put("cadence", row.getCadence());
        result.put("windowPoints", row.getWindowPoints());
        result.put("points", row.getPoints());
        result.put("periodStart", row.getPeriodStart());
        result.put("periodEnd", row.getPeriodEnd());
        result.put("score", row.getScore() == null ? null : row.getScore().toPlainString());
        result.put("status", row.getStatus());
        result.put("signals", truncate(row.getSignals(), MAX_RESULT_CHARS));
        // 判断时看到的数据本身：源可能已经变了，这份是唯一还能复核的依据
        JsonNode snapshot = row.getSnapshotJson() == null ? null : JSON.readTree(row.getSnapshotJson());
        if (snapshot == null) {
            result.put("snapshot", "missing");
        } else {
            result.put("snapshotRetrievedAt", snapshot.path("retrieved_at").asString(""));
            result.put("snapshotDataSha256", snapshot.path("data_sha256").asString(""));
            result.put("snapshotSourcePoints", snapshot.path("source_points").asInt(0));
            result.put("snapshotTruncated", snapshot.path("truncated").asBoolean(false));
            result.put("snapshotPoints", tail(snapshot.path("points"), MAX_POINTS));
        }
        return result;
    }

    @Tool(name = "query_trend_series",
        description = "按数据源 key 取最近若干点的序列快照，用来核对变化幅度与口径",
        readOnly = true)
    public Map<String, Object> queryTrendSeries(
        @ToolParam(name = "sourceKey", description = "数据源 key，例如 frankfurter-audcny", required = true) String sourceKey,
        @ToolParam(name = "points", description = "取最近几个点，2 到 60，默认 30", required = false) int points) {

        acquireBudget();
        int bounded = points <= 0 ? DEFAULT_POINTS : Math.min(points, MAX_POINTS);
        JsonNode snapshot = datasets.snapshot(sourceKey, Math.max(2, bounded));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceKey", sourceKey);
        // 数据点在 rows 下；早先这里读的是 points，于是永远返回 0 行，被误当成「源是空的」
        JsonNode rows = snapshot.path("rows");
        // 请求点数与实际点数要分开给：源本身可能没有那么多历史，模型没法从结果里看出这一点
        result.put("requestedPoints", bounded);
        result.put("returnedPoints", rows.isArray() ? rows.size() : 0);
        result.put("contract", snapshot.path("contract").asString(""));
        result.put("shape", snapshot.path("shape").asString(""));
        result.put("cadence", snapshot.path("cadence").asString(""));
        result.put("retrievedAt", snapshot.path("retrieved_at").asString(""));
        result.put("metric", snapshot.path("metric").path("key").asString(""));
        result.put("unit", snapshot.path("metric").path("unit").asString(""));
        result.put("columns", truncate(snapshot.path("columns").toString(), MAX_RESULT_CHARS / 4));
        result.put("points", tail(rows, bounded));
        return result;
    }

    /** 取序列末尾若干点；返回文本而不是 JsonNode，见类注释。 */
    private static List<String> tail(JsonNode array, int limit) {
        List<String> rows = new ArrayList<>();
        if (!array.isArray()) {
            return rows;
        }
        int budget = MAX_RESULT_CHARS;
        int from = Math.max(0, array.size() - limit);
        for (int i = from; i < array.size(); i++) {
            String row = array.get(i).toString();
            if (row.length() > budget) {
                break;
            }
            budget -= row.length();
            rows.add(row);
        }
        return rows;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
