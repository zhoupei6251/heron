package org.dromara.heron.agentscope;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.dromara.heron.service.impl.DatasetSignal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究层的只读工具面：Agent 只能通过这里看数据，没有任何写入口。
 *
 * <p>返回类型只用 String / int / boolean / List / Map，不用 {@code tools.jackson} 的 JsonNode——
 * AgentScope 用 Jackson 2 序列化工具入出参，本项目的 JsonNode 是 Jackson 3，直接传会踩两栈混用。
 *
 * <p>取值都设上限：一次查询的返回会进模型上下文，不设上限等于让单个工具吃掉预算。
 * 越界参数直接拒绝而不是悄悄夹紧——夹紧会让模型以为自己查到了想要的范围。
 */
public class HeronResearchTools {

    /** 序列点数上限。 */
    static final int MAX_POINTS = 60;
    /** 默认点数：模型不传时用它。 */
    static final int DEFAULT_POINTS = 30;
    /** 最少的点：少于两个点算不出变化。 */
    static final int MIN_POINTS = 2;
    /** 单次工具返回的字符上限。 */
    static final int MAX_RESULT_CHARS = 4000;
    /** 列表类工具一次最多回多少行。 */
    static final int MAX_LIST_ROWS = 20;
    /** 交叉比较一次最多几个源。 */
    static final int MAX_COMPARE_KEYS = 3;
    /** 比较时要取够基线点数，否则 z-score 算不出来；与 DatasetSignal 的基线长度保持一致。 */
    static final int COMPARE_BASELINE_POINTS = 30;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DatasetFetchService datasets;
    private final HeronDataOpportunityMapper opportunities;
    private final HeronDataSourceMapper sources;
    private final HeronVideoVersionMapper videos;
    /** 每次研究一份；为 null 表示不限（单测与冒烟用）。 */
    private final ResearchBudget budget;
    /** 每次研究一份：模型主动收尾时写结论到这里。 */
    private final ResearchFindings findings;

    public HeronResearchTools(DatasetFetchService datasets, HeronDataOpportunityMapper opportunities,
                              HeronDataSourceMapper sources, HeronVideoVersionMapper videos) {
        this(datasets, opportunities, sources, videos, null, new ResearchFindings());
    }

    public HeronResearchTools(DatasetFetchService datasets, HeronDataOpportunityMapper opportunities,
                              HeronDataSourceMapper sources, HeronVideoVersionMapper videos,
                              ResearchBudget budget, ResearchFindings findings) {
        this.datasets = datasets;
        this.opportunities = opportunities;
        this.sources = sources;
        this.videos = videos;
        this.budget = budget;
        this.findings = findings;
    }

    /** 同协作者、换一份预算的副本：每次研究一个预算，不共享。 */
    public HeronResearchTools withBudget(ResearchBudget budget) {
        return new HeronResearchTools(datasets, opportunities, sources, videos, budget, findings);
    }

    /** 同协作者、换一份结论容器的副本：每次研究一份，不共享。 */
    public HeronResearchTools withFindings(ResearchFindings findings) {
        return new HeronResearchTools(datasets, opportunities, sources, videos, budget, findings);
    }

    // ==================== 预算与参数闸 ====================

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
        if (ResearchBudget.TIME_EXCEEDED.equals(rejection)) {
            throw new HeronBusinessException(429, ResearchBudget.TIME_EXCEEDED,
                "本次调查时长已到上限（" + budget.maxDuration().toMinutes() + " 分钟），请基于已获得的信息给出结论");
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

    /** 必填文本：空值直接拒绝，别让下游源 key 变成空串去找。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new HeronBusinessException(400, "invalid_argument", field + " 不能为空");
        }
        return value.trim();
    }

    /** 0 表示「没传」；显式给了值就必须落在允许区间里。 */
    static int boundedPoints(int points) {
        if (points == 0) {
            return DEFAULT_POINTS;
        }
        if (points < MIN_POINTS || points > MAX_POINTS) {
            throw new HeronBusinessException(400, "invalid_argument",
                "points 必须在 " + MIN_POINTS + " 到 " + MAX_POINTS + " 之间，收到 " + points);
        }
        return points;
    }

    private static int boundedLimit(int limit) {
        if (limit <= 0) {
            return MAX_LIST_ROWS;
        }
        if (limit > MAX_LIST_ROWS) {
            throw new HeronBusinessException(400, "invalid_argument",
                "limit 最多 " + MAX_LIST_ROWS + "，收到 " + limit);
        }
        return limit;
    }

    // ==================== 机会与序列 ====================

    @Tool(name = "query_opportunities",
        description = "列出近期的数据机会（信号触发的候选），可按状态过滤；用于了解候选池里还有什么",
        readOnly = true)
    public Map<String, Object> queryOpportunities(
        @ToolParam(name = "status", description = "按状态过滤，例如 new / observed / rejected，可省略", required = false) String status,
        @ToolParam(name = "limit", description = "返回几条，1 到 20，默认 20", required = false) int limit) {

        acquireBudget();
        int bounded = boundedLimit(limit);
        List<HeronDataOpportunity> rows = opportunities.selectList(QueryBuilder
            .lambda(HeronDataOpportunity.class)
            .eqIfText(HeronDataOpportunity::getStatus, status)
            .orderByDesc(HeronDataOpportunity::getObservedAt)
            .last("LIMIT " + bounded)
            .build());

        List<Map<String, Object>> items = new ArrayList<>();
        for (HeronDataOpportunity row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("opportunityId", row.getOpportunityId());
            item.put("headline", row.getHeadline());
            item.put("sourceKey", row.getSourceKey());
            item.put("entity", row.getEntity());
            item.put("metric", row.getMetric());
            item.put("score", row.getScore() == null ? null : row.getScore().toPlainString());
            item.put("status", row.getStatus());
            item.put("periodStart", row.getPeriodStart());
            item.put("periodEnd", row.getPeriodEnd());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("opportunities", items);
        return result;
    }

    @Tool(name = "query_opportunity_detail",
        description = "按 id 查一条数据机会：来源、实体、指标、评分与信号明细",
        readOnly = true)
    public Map<String, Object> queryOpportunityDetail(
        @ToolParam(name = "opportunityId", description = "机会 id", required = true) long opportunityId) {

        acquireBudget();
        if (opportunityId <= 0) {
            throw new HeronBusinessException(400, "invalid_argument", "opportunityId 必须是正整数");
        }
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
        String key = requireText(sourceKey, "sourceKey");
        int bounded = boundedPoints(points);
        JsonNode snapshot = datasets.snapshot(key, bounded);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceKey", key);
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

    @Tool(name = "query_dataset_snapshot",
        description = "按数据源 key 看数据集的结构与边界：字段、形态、更新频率、总点数以及首尾各几点样本",
        readOnly = true)
    public Map<String, Object> queryDatasetSnapshot(
        @ToolParam(name = "sourceKey", description = "数据源 key", required = true) String sourceKey) {

        acquireBudget();
        String key = requireText(sourceKey, "sourceKey");
        JsonNode snapshot = datasets.snapshot(key, DEFAULT_POINTS);
        JsonNode rows = snapshot.path("rows");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceKey", key);
        result.put("contract", snapshot.path("contract").asString(""));
        result.put("shape", snapshot.path("shape").asString(""));
        result.put("cadence", snapshot.path("cadence").asString(""));
        result.put("retrievedAt", snapshot.path("retrieved_at").asString(""));
        result.put("metric", snapshot.path("metric").path("key").asString(""));
        result.put("unit", snapshot.path("metric").path("unit").asString(""));
        result.put("columns", truncate(snapshot.path("columns").toString(), MAX_RESULT_CHARS / 4));
        result.put("totalRows", rows.isArray() ? rows.size() : 0);
        result.put("firstRows", head(rows, 2));
        result.put("lastRows", tail(rows, 2));
        return result;
    }

    @Tool(name = "compare_series",
        description = "把一到三个数据源的近期窗口与各自基线比较，返回变化率与 z-score；用来交叉验证某条信号是不是真的极端",
        readOnly = true)
    public Map<String, Object> compareSeries(
        @ToolParam(name = "sourceKeys", description = "逗号分隔的数据源 key，最多 3 个", required = true) String sourceKeys,
        @ToolParam(name = "points", description = "比较窗口的点数，2 到 60，默认 7", required = false) int points) {

        acquireBudget();
        String raw = requireText(sourceKeys, "sourceKeys");
        List<String> keys = new ArrayList<>();
        for (String part : raw.split(",")) {
            String key = part.trim();
            if (key.isEmpty()) {
                continue;
            }
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            throw new HeronBusinessException(400, "invalid_argument", "sourceKeys 里没有有效的 key");
        }
        if (keys.size() > MAX_COMPARE_KEYS) {
            throw new HeronBusinessException(400, "invalid_argument",
                "一次最多比较 " + MAX_COMPARE_KEYS + " 个源，收到 " + keys.size() + " 个");
        }
        int window = points == 0 ? 7 : boundedPoints(points);

        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : keys) {
            items.add(compareOne(key, window));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowPoints", window);
        result.put("compared", items.size());
        result.put("series", items);
        return result;
    }

    /** 单个源的窗口 vs 基线；算不出有意义信号时如实说「不支持」而不是编一个 0。 */
    private Map<String, Object> compareOne(String sourceKey, int window) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceKey", sourceKey);
        JsonNode snapshot;
        try {
            snapshot = datasets.snapshot(sourceKey, Math.max(window, COMPARE_BASELINE_POINTS));
        } catch (RuntimeException e) {
            item.put("status", "unavailable");
            item.put("reason", truncate(e.getMessage(), 200));
            return item;
        }
        DatasetSignal.Signal signal = DatasetSignal.of(snapshot, window);
        if (signal == null) {
            item.put("status", "unsupported");
            item.put("reason", "这个源算不出有意义信号：形态不支持、点数不足、数据过期或类别不参与打分");
            return item;
        }
        item.put("status", "ok");
        item.put("entity", signal.entity());
        item.put("metric", signal.metric());
        item.put("periodStart", signal.periodStart());
        item.put("periodEnd", signal.periodEnd());
        item.put("points", signal.points());
        item.put("coverage", signal.coverage());
        item.put("changePct", signal.changePct());
        item.put("zscore", signal.zscore());
        item.put("extreme", signal.extreme());
        item.put("signalType", signal.signalType());
        item.put("flags", signal.flags());
        return item;
    }

    // ==================== 源与产出 ====================

    @Tool(name = "query_data_sources",
        description = "列出已注册的数据源：分类、更新频率、是否可商用、是否启用；用来找还能查什么",
        readOnly = true)
    public Map<String, Object> queryDataSources(
        @ToolParam(name = "category", description = "按分类过滤，可省略", required = false) String category) {

        acquireBudget();
        List<HeronDataSource> rows = sources.selectList(QueryBuilder
            .lambda(HeronDataSource.class)
            .eqIfText(HeronDataSource::getCategory, category)
            .orderByAsc(HeronDataSource::getSourceKey)
            .last("LIMIT " + MAX_LIST_ROWS)
            .build());

        List<Map<String, Object>> items = new ArrayList<>();
        for (HeronDataSource row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceKey", row.getSourceKey());
            item.put("name", row.getName());
            item.put("category", row.getCategory());
            item.put("cadence", row.getCadence());
            item.put("shape", row.getShapes());
            item.put("commercialOk", row.getCommercialOk());
            item.put("enabled", "0".equals(row.getEnabled()));
            item.put("probeStatus", row.getProbeStatus());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("sources", items);
        return result;
    }

    @Tool(name = "query_source_health",
        description = "列出现在取不到数的数据源：探测失败、无权限或被拦截，以及累计失败次数；用来解释为什么查不到数据",
        readOnly = true)
    public Map<String, Object> querySourceHealth() {
        acquireBudget();
        List<HeronDataSource> unhealthy = sources.selectList(QueryBuilder
            .lambda(HeronDataSource.class)
            .ne(HeronDataSource::getProbeStatus, "ok")
            .orderByDesc(HeronDataSource::getFailureCount)
            .last("LIMIT " + MAX_LIST_ROWS)
            .build());

        List<Map<String, Object>> items = new ArrayList<>();
        for (HeronDataSource row : unhealthy) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceKey", row.getSourceKey());
            item.put("name", row.getName());
            item.put("probeStatus", row.getProbeStatus());
            item.put("probeNote", truncate(row.getProbeNote(), 200));
            item.put("failureCount", row.getFailureCount());
            item.put("lastUpdated", text(row.getLastUpdated()));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unhealthyCount", items.size());
        result.put("sources", items);
        return result;
    }

    @Tool(name = "query_recent_productions",
        description = "列出最近的视频版本：做了哪条、审没审过、发没发出去；用来避免重复选题和了解已产出内容",
        readOnly = true)
    public Map<String, Object> queryRecentProductions(
        @ToolParam(name = "limit", description = "返回几条，1 到 20，默认 10", required = false) int limit) {

        acquireBudget();
        int bounded = limit <= 0 ? 10 : boundedLimit(limit);
        List<HeronVideoVersion> rows = videos.selectList(QueryBuilder
            .lambda(HeronVideoVersion.class)
            .orderByDesc(HeronVideoVersion::getCreateTime)
            .last("LIMIT " + bounded)
            .build());

        List<Map<String, Object>> items = new ArrayList<>();
        for (HeronVideoVersion row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("versionId", row.getVersionId() == null ? null : String.valueOf(row.getVersionId()));
            item.put("workId", row.getWorkId() == null ? null : String.valueOf(row.getWorkId()));
            item.put("versionNo", row.getVersionNo());
            item.put("reviewStatus", row.getReviewStatus());
            item.put("reviewReason", truncate(row.getReviewReason(), 200));
            item.put("durationMs", row.getDurationMs());
            item.put("publishSource", row.getPublishSource());
            item.put("publishAt", text(row.getPublishAt()));
            item.put("createdAt", text(row.getCreateTime()));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("productions", items);
        return result;
    }

    // ==================== 收尾 ====================

    /**
     * 主动收尾：证据够了就停，不再多花一轮。
     *
     * <p>{@code returnDirect = true} 让这次结果直接成为本轮最终回答、跳过下一轮推理。
     * 这正是 plan.md §5.3「已找到足够证据支持结论」与「继续调查的边际价值较低」两条的落地方式——
     * 没有它，模型只能一路查到轮数到顶。
     *
     * <p>不过闸：收尾不查数据，不该占工具名额，否则预算刚好用尽时模型连结论都交不出来。
     */
    @Tool(name = "submit_findings",
        description = "调查到此为止：把结论交出来。证据已经够、或者再查也不会有新东西时调用它，不要为了多用几次工具而继续查",
        readOnly = true,
        returnDirect = true)
    public String submitFindings(
        @ToolParam(name = "decision", description = "PRODUCE 值得做 / OBSERVE 还需要更多数据 / REJECT 不值得", required = true) String decision,
        @ToolParam(name = "reason", description = "为什么，一两句话，要指向具体数字", required = true) String reason,
        @ToolParam(name = "evidence", description = "支撑这个判断的数据源 key 或机会 id，每个是一个字符串；只写真的查过的", required = false) List<String> evidence,
        @ToolParam(name = "angle", description = "如果决定做，从哪个角度讲；不做就留空", required = false) String angle,
        @ToolParam(name = "confidence", description = "把握程度 0 到 1，可省略", required = false) double confidence) {

        String normalized = requireText(decision, "decision").toUpperCase();
        if (!ResearchFindings.PRODUCE.equals(normalized)
            && !ResearchFindings.OBSERVE.equals(normalized)
            && !ResearchFindings.REJECT.equals(normalized)) {
            throw new HeronBusinessException(400, "invalid_argument",
                "decision 只能是 PRODUCE / OBSERVE / REJECT，收到 " + decision);
        }
        String why = requireText(reason, "reason");
        if (confidence < 0 || confidence > 1) {
            throw new HeronBusinessException(400, "invalid_argument",
                "confidence 必须在 0 到 1 之间，收到 " + confidence);
        }
        // 0 表示模型没传（double 参数拿不到 null），当未知而不是「零把握」
        BigDecimal trust = confidence == 0 ? null : BigDecimal.valueOf(confidence);
        findings.record(normalized, why,
            angle == null || angle.isBlank() ? null : angle.trim(), trust, evidence);

        return "结论已记录：decision=" + normalized + "。" + why;
    }

    // ==================== 工具方法 ====================

    /** 取序列开头若干点；返回文本而不是 JsonNode，见类注释。 */
    private static List<String> head(JsonNode array, int limit) {
        List<String> rows = new ArrayList<>();
        if (!array.isArray()) {
            return rows;
        }
        int budget = MAX_RESULT_CHARS / 2;
        for (int i = 0; i < Math.min(limit, array.size()); i++) {
            String row = array.get(i).toString();
            if (row.length() > budget) {
                break;
            }
            budget -= row.length();
            rows.add(row);
        }
        return rows;
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

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
