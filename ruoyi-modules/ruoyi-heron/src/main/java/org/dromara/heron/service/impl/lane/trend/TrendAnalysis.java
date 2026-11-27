package org.dromara.heron.service.impl.lane.trend;

import org.dromara.heron.domain.EditorialSelection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 趋势线的确定性判定器：跨时间点分析，产出 trend/1。
 *
 * <p>趋势线讲「变化意味着什么」，所以判定必须是算出来的，不能是模型说的：
 * 同一个序列换一个模型就换一个结论的判定，对外不可用。这里只做三件事——
 * 把一条时间序列切成三段比中位数、量同向点对的占比、量区间内的波动幅度，
 * 然后把它归类成「持续上行 / 持续下行 / 区间波动 / 样本不足」之一。
 *
 * <p>四类判定对应四种不能说的话：区间波动不许说成长期趋势，样本不足不许判方向，
 * 变化原因与后续走势一律是推测（由 {@link org.dromara.heron.service.impl.lane.trend.TrendContract}
 * 在脚本层挡）。
 *
 * <p>多来源证据：主来源出判定，其余来源只做交叉核对（同向记 consistent，
 * 反向或持平记 divergent）。分歧不改判定——判定是主来源的事实陈述——
 * 只把分歧写进 disagreements 与 limits，交给脚本层与审稿层去处理。
 */
public final class TrendAnalysis {

    public static final String SCHEMA_VERSION = "trend/1";
    public static final String SUSTAINED_UP = "sustained_up";
    public static final String SUSTAINED_DOWN = "sustained_down";
    public static final String RANGE = "range_fluctuation";
    public static final String INSUFFICIENT = "insufficient";

    /** 少于 8 期不做趋势判定：三段各至少 2 个点，中位数才有意义。 */
    static final int MIN_POINTS = 8;
    /** 相邻两段中位数每段至少变化 5%，才谈得上「持续」。 */
    static final double STEP_PCT = 5.0;
    /** 首末净变化至少 10%，小于这个量归区间波动。 */
    static final double NET_PCT = 10.0;
    static final double FLAT_PCT = 5.0;
    /** 同向点对占比不足六成就是来回震荡，不是单边。 */
    static final double SIGN_AGREEMENT = 0.6;
    /** (max-min)/|中位数| 超过 60% 说明波动主导，即使首末有净变化也不判趋势。 */
    static final double HIGH_VOLATILITY_PCT = 60.0;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TrendAnalysis() { }

    private record Point(String date, double value) { }

    /** 单来源判定，等价于没有其他来源可交叉核对。 */
    public static ObjectNode analyze(JsonNode dataset) {
        return analyze(dataset, List.of());
    }

    /**
     * 主来源出判定，其余来源做交叉核对。
     *
     * @param dataset     主来源 dataset/1 快照
     * @param others      其他来源的同指标快照，可为空；为空时 corroboration=single_source
     */
    public static ObjectNode analyze(JsonNode dataset, List<JsonNode> others) {
        ObjectNode trend = JsonNodeFactory.instance.objectNode();
        trend.put("contract", SCHEMA_VERSION);
        List<Point> points = series(dataset);
        JsonNode metric = dataset.path("metric");
        ObjectNode metricNode = trend.putObject("metric");
        metricNode.put("key", metric.path("key").asString(""));
        metricNode.put("label", metric.path("label").asString(""));
        metricNode.put("unit", metric.path("unit").asString(""));
        metricNode.put("precision", metric.path("precision").asInt(2));
        trend.put("cadence", dataset.path("cadence").asString(""));
        trend.put("points", points.size());

        if (points.size() < MIN_POINTS) {
            trend.put("verdict", INSUFFICIENT);
            trend.put("label", label(INSUFFICIENT));
            trend.putArray("segments");
            trend.put("speculation_boundary", speculationBoundary());
            evidence(trend, dataset, others);
            trend.putArray("limits").add("只有 " + points.size() + " 期，少于 " + MIN_POINTS
                + " 期，不做趋势判定；画面只能讲已发生的区间变化");
            return trend;
        }

        double first = points.get(0).value();
        double last = points.get(points.size() - 1).value();
        double min = points.stream().mapToDouble(Point::value).min().orElse(first);
        double max = points.stream().mapToDouble(Point::value).max().orElse(last);
        double netPct = pct(first, last);
        double[] medians = medians(points);
        double shiftPct = pct(medians[0], medians[2]);
        double volatility = volatility(min, max, medians);
        double agreement = agreement(points);

        String verdict;
        if (volatility >= HIGH_VOLATILITY_PCT) {
            verdict = RANGE;
        } else if (medians[0] < medians[1] && medians[1] < medians[2]
            && shiftPct >= STEP_PCT && netPct >= NET_PCT && agreement >= SIGN_AGREEMENT) {
            verdict = SUSTAINED_UP;
        } else if (medians[0] > medians[1] && medians[1] > medians[2]
            && shiftPct <= -STEP_PCT && netPct <= -NET_PCT && agreement >= SIGN_AGREEMENT) {
            verdict = SUSTAINED_DOWN;
        } else {
            verdict = RANGE;
        }

        trend.put("verdict", verdict);
        trend.put("label", label(verdict));
        ObjectNode period = trend.putObject("period");
        period.put("from", points.get(0).date());
        period.put("to", points.get(points.size() - 1).date());
        point(trend.putObject("first"), points.get(0));
        point(trend.putObject("last"), points.get(points.size() - 1));
        trend.putObject("min").put("date", dateOf(points, min, true)).put("value", round2(min));
        trend.putObject("max").put("date", dateOf(points, max, false)).put("value", round2(max));
        trend.put("median_shift_pct", round2(shiftPct));
        trend.put("net_change_pct", round2(netPct));
        trend.put("sign_agreement", round2(agreement));
        trend.put("volatility_pct", round2(volatility));
        segments(trend, points, medians, shiftPct);

        // 阈值一并落库：判定是可复核的算式，不是黑箱结论
        ObjectNode thresholds = trend.putObject("thresholds");
        thresholds.put("min_points", MIN_POINTS);
        thresholds.put("step_pct", STEP_PCT);
        thresholds.put("net_pct", NET_PCT);
        thresholds.put("flat_pct", FLAT_PCT);
        thresholds.put("sign_agreement", SIGN_AGREEMENT);
        thresholds.put("high_volatility_pct", HIGH_VOLATILITY_PCT);

        trend.put("speculation_boundary", speculationBoundary());
        plan(trend, metric.path("label").asString(""), metric.path("unit").asString(""), verdict,
            points, medians);
        evidence(trend, dataset, others);
        var limits = trend.putArray("limits");
        limits.add("只描述 " + points.get(0).date() + " 到 " + points.get(points.size() - 1).date()
            + " 共 " + points.size() + " 期，不外推为长期趋势");
        limits.add("未做季节性、基数效应与口径变动的调整：突变的拐点可能来自统计口径，而不是经济含义");
        limits.add("变化原因与后续走势属于推测，需另行取证后才能上画面");
        if ("single_source".equals(trend.path("corroboration").asString(""))) {
            limits.add("单一来源，未做交叉核对：口径本身对不对，这份数据证明不了");
        }
        if ("divergent".equals(trend.path("corroboration").asString(""))) {
            limits.add("多来源交叉核对出现分歧，旁白不得给出单一方向的结论");
        }
        return trend;
    }

    /**
     * 有额外来源时，把交叉核对与由此产生的限制补进同一份 trend/1。
     * 主来源一条，其他来源逐条比对方向；期数不足或方向不一致记 divergent 并留下分歧说明。
     */
    private static void evidence(ObjectNode trend, JsonNode dataset, List<JsonNode> others) {
        var evidence = trend.withArray("evidence");
        ObjectNode primary = JsonNodeFactory.instance.objectNode();
        primary.put("role", "primary");
        primary.put("source_key", dataset.path("source_key").asString(""));
        primary.put("source_name", dataset.path("source_name").asString(""));
        primary.put("data_sha256", dataset.path("data_sha256").asString(""));
        primary.put("points", series(dataset).size());
        primary.put("from", dataset.path("time_range").path("from").asString(""));
        primary.put("to", dataset.path("time_range").path("to").asString(""));
        evidence.add(primary);
        List<String> disagreements = new ArrayList<>();
        String corroboration = others == null || others.isEmpty() ? "single_source" : "consistent";
        // 主来源的方向：只有主来源本身有明确方向时，交叉核对才谈得上「同向 / 反向」；
        // 主来源是区间波动或样本不足时，别的来源说什么都不构成一致或不一致。
        String primaryDirection = trend.has("net_change_pct")
            ? direction(trend.path("net_change_pct").asDouble(-0.0)) : "";
        for (JsonNode other : others == null ? List.<JsonNode>of() : others) {
            List<Point> points = series(other);
            ObjectNode item = JsonNodeFactory.instance.objectNode();
            item.put("role", "corroborating");
            item.put("source_key", other.path("source_key").asString(""));
            item.put("source_name", other.path("source_name").asString(""));
            item.put("data_sha256", other.path("data_sha256").asString(""));
            item.put("points", points.size());
            item.put("from", other.path("time_range").path("from").asString(""));
            item.put("to", other.path("time_range").path("to").asString(""));
            evidence.add(item);
            if (points.size() < MIN_POINTS) {
                corroboration = "divergent";
                disagreements.add(other.path("source_key").asString("") + " 只有 " + points.size() + " 期，无法核对");
                continue;
            }
            double netPct = pct(points.get(0).value(), points.get(points.size() - 1).value());
            String otherDirection = direction(netPct);
            ObjectNode result = item.putObject("result");
            result.put("net_change_pct", round2(netPct));
            result.put("direction", otherDirection);
            if (primaryDirection.isEmpty()) {
                continue;
            }
            if ("flat".equals(otherDirection)) {
                corroboration = "divergent";
                disagreements.add(other.path("source_key").asString("") + " 首末基本持平，与主来源方向不一致");
            } else if (!otherDirection.equals(primaryDirection)) {
                corroboration = "divergent";
                disagreements.add(other.path("source_key").asString("") + " 走向与主来源相反（"
                    + otherDirection + " vs " + primaryDirection + "）");
            }
        }
        trend.put("corroboration", corroboration);
        trend.putArray("disagreements").addAll(toNodes(disagreements));
    }

    private static tools.jackson.databind.node.ArrayNode toNodes(List<String> texts) {
        var array = JsonNodeFactory.instance.arrayNode();
        texts.forEach(array::add);
        return array;
    }

    /**
     * 趋势选题的准入判定：形态必须是时间序列、期数够、节奏已知。
     * 不合格的机会不进趋势线池子——进去也只能写出「一条没有趋势数据的趋势片」。
     */
    public static List<String> opportunityViolations(EditorialSelection.Opportunity opportunity) {
        List<String> out = new ArrayList<>();
        if (opportunity == null) {
            out.add("趋势选题必须来自数据机会");
            return out;
        }
        if (!"wide".equals(opportunity.shape())) {
            out.add("趋势选题需要时间序列形态（wide），当前形态是 " + opportunity.shape());
        }
        if (opportunity.windowPoints() < MIN_POINTS) {
            out.add("取数点不足 " + MIN_POINTS + " 期（当前 " + opportunity.windowPoints() + " 期），无法判定趋势");
        }
        if (opportunity.cadence() == null || opportunity.cadence().isBlank()) {
            out.add("缺少取数节奏（cadence），无法说明这是多久一次的变化");
        }
        return out;
    }

    /**
     * 机会能否进趋势线：形态必须是 wide、期数达到 {@link #MIN_POINTS}、cadence 已知。
     *
     * @param opportunity 数据机会，可为 null
     * @return true 表示可以路由到趋势线；false 时具体原因见 {@link #opportunityViolations}
     */
    public static boolean routable(EditorialSelection.Opportunity opportunity) {
        return opportunityViolations(opportunity).isEmpty();
    }

    /** 判定的中文名：画面与提示词都用这一个来源，免得两处各写一份。 */
    public static String label(String verdict) {
        return switch (verdict == null ? "" : verdict) {
            case SUSTAINED_UP -> "持续上行";
            case SUSTAINED_DOWN -> "持续下行";
            case RANGE -> "区间波动";
            case INSUFFICIENT -> "样本不足";
            default -> "样本不足";
        };
    }

    /** 判定是否落在一个明确方向上；只有明确方向才允许旁白说「持续」。 */
    public static boolean directional(JsonNode trend) {
        String verdict = trend == null ? "" : trend.path("verdict").asString("");
        return SUSTAINED_UP.equals(verdict) || SUSTAINED_DOWN.equals(verdict);
    }

    /** 提示词用的口径摘要：判定、三段中位数、波动率、边界，一段话给完。 */
    public static String summary(JsonNode trend) {
        if (trend == null || !trend.isObject()) {
            return "（未产出趋势判定）";
        }
        StringBuilder text = new StringBuilder()
            .append("判定：").append(trend.path("label").asString(""))
            .append("（").append(trend.path("verdict").asString("")).append("）");
        JsonNode segments = trend.path("segments");
        if (segments.isArray() && !segments.isEmpty()) {
            text.append("；三段中位数：");
            for (int index = 0; index < segments.size(); index++) {
                if (index > 0) text.append(" → ");
                text.append(segments.get(index).path("median").asDouble());
            }
            text.append("（整体位移 ").append(trend.path("median_shift_pct").asDouble()).append("%）");
        }
        text.append("；首末变化 ").append(trend.path("net_change_pct").asDouble()).append("%")
            .append("；同向点对 ").append(trend.path("sign_agreement").asDouble())
            .append("；区间波动 ").append(trend.path("volatility_pct").asDouble()).append("%")
            .append("；来源：").append(trend.path("corroboration").asString(""))
            .append('\n').append("边界：").append(trend.path("speculation_boundary").asString(""));
        return text.toString();
    }

    private static void segments(ObjectNode trend, List<Point> points, double[] medians, double shiftPct) {
        var segments = trend.putArray("segments");
        int from = 0;
        for (int index = 0; index < 3; index++) {
            int to = index == 2 ? points.size() : (points.size() * (index + 1)) / 3;
            ObjectNode item = segments.addObject();
            item.put("index", index + 1);
            item.put("from", points.get(from).date());
            item.put("to", points.get(to - 1).date());
            item.put("points", to - from);
            item.put("median", round2(medians[index]));
            String direction = index == 0 ? "flat" : (medians[index] > medians[index - 1] ? "up"
                : medians[index] < medians[index - 1] ? "down" : "flat");
            item.put("direction", direction);
            // 末段的方向就是整体位移方向，前两段分别是「第一步」「第二步」，三段连起来才构成「持续」
            from = to;
        }
        trend.put("shift_direction", direction(shiftPct));
    }

    /**
     * 六段叙事计划：问题 → 现象 → 证据 → 解释 → 洞察 → 待观察。
     *
     * <p>计划随 trend/1 一起落库，脚本与审稿都读同一份：写稿跑偏时能指出「哪一段没写」，
     * 而不是事后凭印象说「这条稿子有点空」。
     */
    private static void plan(ObjectNode trend, String metricLabel, String unit, String verdict,
                             List<Point> points, double[] medians) {
        String name = metricLabel == null || metricLabel.isBlank() ? "这个指标" : metricLabel;
        String range = points.get(0).date() + " 到 " + points.get(points.size() - 1).date();
        ObjectNode plan = trend.putObject("plan");
        plan.put("question", question(name, verdict, range, points.size()));
        var beats = plan.putArray("beats");
        beats.add("问题：" + question(name, verdict, range, points.size()));
        beats.add("现象：先讲区间内看到的样子（" + range + "，共 " + points.size() + " 期），结论指向「"
            + label(verdict) + "」");
        beats.add("证据：三段中位数 " + round2(medians[0]) + " → " + round2(medians[1]) + " → "
            + round2(medians[2]) + "，并交代同向点对与区间波动幅度");
        beats.add("解释：只解释已经发生的位移怎么发生的，把原因标注为待验证；不得把原因写成结论");
        beats.add("洞察：说明这个位移对读者意味着什么——只说含义，不预测后续");
        beats.add("待观察：明确还需要哪些证据才能把推测变成结论");
        var mustNot = plan.putArray("must_not");
        switch (verdict) {
            case SUSTAINED_UP, SUSTAINED_DOWN -> mustNot.add("不得把这一段位移说成长期趋势，除非另有更长区间的证据");
            case RANGE -> {
                mustNot.add("不得把区间内的来回说成持续上涨或持续下跌");
                mustNot.add("不得只挑区间两端或最高最低两点来暗示单边走势");
            }
            default -> {
                mustNot.add("样本不足，不得给涨跌方向");
                mustNot.add("不得用「趋势」二字描述这条数据");
            }
        }
        mustNot.add("不得把任何推测写成确定事实（预计/将会/必然/一定会之类的断言）");
        mustNot.add("不得出现工具无法从快照追溯的数字与时间口径");
        if (unit != null && !unit.isBlank()) {
            plan.put("unit", unit);
        }
    }

    private static String question(String name, String verdict, String range, int points) {
        return switch (verdict) {
            case SUSTAINED_UP -> name + " 在 " + range + " 这 " + points + " 期里真的一路在走高，还是只是区间内的来回？";
            case SUSTAINED_DOWN -> name + " 这 " + points + " 期是持续下跌，还是跌完又弹回去的区间波动？";
            case RANGE -> name + " 看起来有变化，但这 " + points + " 期到底是趋势还是来回震荡？";
            default -> name + " 只有 " + points + " 期数据，够不够判断它在往哪儿走？";
        };
    }

    private static String speculationBoundary() {
        return "本判定只陈述所给区间内已经发生的变化；变化原因、拐点成因与后续走势都属于推测，"
            + "必须另有证据才能说，且不能以确定语气播出";
    }

    /** 时间序列：只认 shape=wide 的 [date, value] 行；其它形态没有「跨时间点」可言。 */
    static List<Point> series(JsonNode dataset) {
        JsonNode rows = dataset == null ? null : dataset.path("rows");
        if (rows == null || !rows.isArray()) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 2 || !row.get(1).isNumber()) {
                continue;
            }
            String date = row.get(0).asString("");
            if (date.isBlank()) {
                continue;
            }
            points.add(new Point(date, row.get(1).asDouble()));
        }
        // 适配器按时间序输出；这里仍显式排序，不依赖上游顺序，也顺手去掉重复日期
        points.sort(Comparator.comparing(Point::date));
        List<Point> deduped = new ArrayList<>();
        for (Point item : points) {
            if (!deduped.isEmpty() && deduped.get(deduped.size() - 1).date().equals(item.date())) {
                deduped.set(deduped.size() - 1, item);
            } else {
                deduped.add(item);
            }
        }
        return deduped;
    }

    private static double[] medians(List<Point> points) {
        double[] out = new double[3];
        for (int index = 0; index < 3; index++) {
            int from = (points.size() * index) / 3;
            int to = index == 2 ? points.size() : (points.size() * (index + 1)) / 3;
            List<Double> values = new ArrayList<>();
            for (int at = from; at < to; at++) {
                values.add(points.get(at).value());
            }
            values.sort(Comparator.naturalOrder());
            int size = values.size();
            out[index] = size % 2 == 1 ? values.get(size / 2)
                : (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
        }
        return out;
    }

    /** 同向点对占比：相邻差值是涨的多还是跌的多。持平的差值不投票。 */
    private static double agreement(List<Point> points) {
        int up = 0;
        int down = 0;
        for (int index = 1; index < points.size(); index++) {
            double delta = points.get(index).value() - points.get(index - 1).value();
            if (delta > 0) up++;
            else if (delta < 0) down++;
        }
        int voted = up + down;
        return voted == 0 ? 0 : Math.max(up, down) / (double) voted;
    }

    /** 波动率：(max-min)/|中位数|。中位数为 0 时退回相对极值，避免除零。 */
    private static double volatility(double min, double max, double[] medians) {
        double base = Math.abs(medians[1]);
        if (base < 1e-9) {
            base = Math.max(Math.abs(min), Math.abs(max));
        }
        return base < 1e-9 ? 0 : (max - min) / base * 100.0;
    }

    private static double pct(double from, double to) {
        if (Math.abs(from) < 1e-9) {
            return to == 0 ? 0 : (to > 0 ? 100.0 : -100.0);
        }
        return (to - from) / Math.abs(from) * 100.0;
    }

    private static String direction(double pct) {
        if (pct >= FLAT_PCT) return "up";
        if (pct <= -FLAT_PCT) return "down";
        return "flat";
    }

    private static void point(ObjectNode node, Point point) {
        node.put("date", point.date());
        node.put("value", round2(point.value()));
    }

    private static String dateOf(List<Point> points, double value, boolean firstMatch) {
        String date = points.get(0).date();
        for (Point point : points) {
            if (point.value() == value) {
                date = point.date();
                if (firstMatch) return date;
            }
        }
        return date;
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 判定结果的标准 JSON 文本：落库、重放、审稿都用这一份。 */
    static String text(ObjectNode trend) {
        return JSON.writeValueAsString(trend);
    }
}
