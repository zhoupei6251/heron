package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据信号：从一份 dataset/1 快照里算出「最近这段变了多少、离平时多远」。
 *
 * <p>纯函数，不出网、不读库、不调模型。之所以单独成类：阈值要拿真实数据反复校准，
 * 能在单测里直接喂序列比跑一整条生产链路快得多。
 *
 * <p>窗口一律按「点」算，并把 cadence 一起带出去。同一个「30」对 daily 是 30 天、
 * 对 annual 是 30 年——不写清楚就会重演 DatasetFacts.change 那种含义漂移。
 */
public final class DatasetSignal {

    /** 计算 z-score 用的基线长度（不含最新一点）。 */
    static final int BASELINE_POINTS = 30;
    /** 少于这么多点就不算 z-score：样本太少的「异常」几乎都是噪声。 */
    static final int MIN_BASELINE_POINTS = 5;
    /** 短期变化窗口，按点。 */
    static final int SHORT_WINDOW = 7;
    /** z-score 的封顶值，超过就当作同样极端，避免除数接近 0 时爆表。 */
    static final double Z_CAP = 5.0;
    /**
     * 超过这个幅度的「变化」按数据口径变化处理，不当新闻。
     * 实测里 eastmoney-ppi 出现过 7 期 +1040%——那是口径/单位换了，不是市场事件。
     */
    static final double MAX_PLAUSIBLE_CHANGE = 2.0;

    private DatasetSignal() {
    }

    /**
     * 类别的「大变化」标尺：同一个数字在不同类别里含义完全不同。
     *
     * <p>宏观指标动 0.3% 就是新闻，股指动 0.3% 什么都不是；天气预报天天在变，
     * 压根不该参与「今天发生了什么」；计数与排行的百分比在小编数上没有意义。
     *
     * @param percentMatters 百分比变化是否有意义；false 时只按偏离（z-score）判断
     * @param bigMove        「大变化」的相对幅度基准，用于把不同类别拉回同一量纲
     * @param eligible       该类数据能不能成为选题
     */
    public record CategoryPolicy(boolean percentMatters, double bigMove, boolean eligible) {

        public static CategoryPolicy of(String category) {
            return switch (category == null ? "" : category.trim().toLowerCase(java.util.Locale.ROOT)) {
                // 宏观序列本身波动就小：0.3% 已经是新闻
                case "macro", "dbnomics" -> new CategoryPolicy(true, 0.02, true);
                case "finance" -> new CategoryPolicy(true, 0.05, true);
                case "boxoffice" -> new CategoryPolicy(true, 0.20, true);
                // 计数与排行：增长率在小编数上没有意义，只看偏离与极值
                case "platform", "news", "ai", "entertainment", "gaming" ->
                    new CategoryPolicy(false, 0, true);
                // 天气预报天天变，不构成「今天发生了什么」
                case "weather" -> new CategoryPolicy(false, 0, false);
                // 没见过的类别按通用口径放行，不因为分类缺失就静默丢弃
                default -> new CategoryPolicy(true, 0.10, true);
            };
        }

        public static final CategoryPolicy DEFAULT = of("");
    }

    public static Signal of(JsonNode snapshot, int windowPoints) {
        return of(snapshot, windowPoints, java.time.YearMonth.now(), CategoryPolicy.DEFAULT);
    }

    public static Signal of(JsonNode snapshot, int windowPoints, java.time.YearMonth today) {
        return of(snapshot, windowPoints, today, CategoryPolicy.DEFAULT);
    }

    /**
     * 一个源一天只出一条信号：多个实体时取偏离最大的那个。
     *
     * @param windowPoints 请求的点数，用于算覆盖率；与 cadence 一起解释窗口含义
     * @param today        判定「数据是否还新鲜」的基准，显式传入以便单测固定
     * @param policy       该类数据的「大变化」口径
     * @return 算不出有意义信号（形态不支持、点数不足、数据过期、幅度不可信、类别不参与）时返回 null
     */
    public static Signal of(JsonNode snapshot, int windowPoints, java.time.YearMonth today,
                            CategoryPolicy policy) {
        if (policy == null || !policy.eligible()) {
            return null;
        }
        if (snapshot == null || !snapshot.isObject()) {
            return null;
        }
        String shape = snapshot.path("shape").asString("");
        String cadence = snapshot.path("cadence").asString("");
        String metric = snapshot.path("metric").path("key").asString("");
        JsonNode rows = snapshot.path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return null;
        }
        Signal signal = switch (shape) {
            case "wide" -> fromWide(rows, metric, cadence, windowPoints, policy);
            case "long" -> fromLong(rows, metric, cadence, windowPoints, policy);
            case "rows" -> fromRows(rows, metric, cadence, windowPoints, policy);
            // geo 没有时间轴，单期分布本身说明不了「今天变了什么」；先不出机会
            default -> null;
        };
        if (signal == null) {
            return null;
        }
        // 历史序列（年频的 OWID/Gapminder 一类）末点可能是几年前，当「今天变了」用就是假新闻
        if (stale(signal.periodEnd(), cadence, today)) {
            return null;
        }
        // 幅度大到不合常理的多半是口径变了，宁可漏掉也不发一条 +1040%
        if (signal.changePct() != null && Math.abs(signal.changePct()) > MAX_PLAUSIBLE_CHANGE) {
            return null;
        }
        return signal;
    }

    /** 末点早于该 cadence 的合理新鲜度就判过期；日期解析不了时不拦。 */
    static boolean stale(String periodEnd, String cadence, java.time.YearMonth today) {
        java.time.YearMonth end = parseYearMonth(periodEnd);
        if (end == null) {
            return false;
        }
        long months = java.time.temporal.ChronoUnit.MONTHS.between(end, today);
        int tolerance = switch (cadence == null ? "" : cadence) {
            case "realtime", "daily", "weekly" -> 1;
            case "monthly" -> 3;
            case "annual" -> 18;
            default -> 3;
        };
        return months > tolerance;
    }

    /** 只认年月：数据源的日期写法从 `2021` 到 `2021-12-31` 都有，按天解析会误判。 */
    static java.time.YearMonth parseYearMonth(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("^(\\d{4})(?:-(\\d{1,2}))?").matcher(text.trim());
        if (!matcher.find()) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) {
            return null;
        }
        return java.time.YearMonth.of(year, month);
    }

    private static Signal fromWide(JsonNode rows, String metric, String cadence, int windowPoints,
                                   CategoryPolicy policy) {
        List<Point> points = new ArrayList<>();
        for (JsonNode row : rows) {
            Double value = number(row, 1);
            if (value == null) {
                continue;
            }
            points.add(new Point(text(row, 0), value));
        }
        return series(points, "", metric, cadence, windowPoints, "wide", policy);
    }

    /** 多实体序列：每个实体各算一套，取强度最高的那个作为本源的信号。 */
    private static Signal fromLong(JsonNode rows, String metric, String cadence, int windowPoints,
                                   CategoryPolicy policy) {
        Map<String, List<Point>> series = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String name = text(row, 1);
            Double value = number(row, 2);
            if (name.isBlank() || value == null) {
                continue;
            }
            series.computeIfAbsent(name, key -> new ArrayList<>()).add(new Point(text(row, 0), value));
        }
        Signal best = null;
        for (Map.Entry<String, List<Point>> entry : series.entrySet()) {
            Signal candidate = series(entry.getValue(), entry.getKey(), metric, cadence, windowPoints, "long", policy);
            if (candidate != null && (best == null || candidate.magnitude() > best.magnitude())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 单期排行：没有时间轴，只能读适配器给的变化列（第三列）。
     * 适配器不给变化就不出机会——「当前数值大小」不是新闻。
     *
     * <p>单期排行只能按相对幅度读，所以只对「百分比有意义」的类别开放；
     * 榜单类数据的相对幅度说明不了任何事，直接不出机会。
     */
    private static Signal fromRows(JsonNode rows, String metric, String cadence, int windowPoints,
                                   CategoryPolicy policy) {
        if (!policy.percentMatters()) {
            return null;
        }
        String label = "";
        double relative = 0;
        double absChange = 0;
        int changed = 0;
        for (JsonNode row : rows) {
            Double value = number(row, 1);
            Double change = number(row, 2);
            if (value == null || change == null || change == 0) {
                continue;
            }
            changed++;
            double base = Math.abs(value - change);
            // 基期接近 0 时相对变化没有意义，退回按绝对变化排名，避免除出天文数字
            double candidate = base < 1e-9 ? Math.abs(change) : Math.abs(change) / base;
            if (candidate > relative) {
                relative = candidate;
                absChange = Math.abs(change);
                label = text(row, 0);
            }
        }
        if (changed == 0) {
            return null;
        }
        double magnitude = Math.min(relative / Math.max(policy.bigMove(), 1e-9), 1.0);
        List<String> flags = new ArrayList<>();
        flags.add("变化 " + percent(relative));
        return new Signal("rows", label, metric, "", "", rows.size(), windowPoints,
            magnitude, relative, null, false, 1.0, "mover", absChange, policy.bigMove(), flags);
    }

    private static Signal series(List<Point> raw, String entity, String metric, String cadence,
                                 int windowPoints, String shape, CategoryPolicy policy) {
        if (raw.size() < 2) {
            return null;
        }
        List<Point> points = new ArrayList<>(raw);
        // 适配器多数已按时间序输出，这里显式排序，不依赖上游顺序
        points.sort(Comparator.comparing(Point::date));

        Point last = points.get(points.size() - 1);
        List<Point> prior = points.subList(0, points.size() - 1);
        List<Point> baseline = prior.subList(Math.max(0, prior.size() - BASELINE_POINTS), prior.size());

        Double change1 = ratio(last.value(), prior.get(prior.size() - 1).value());
        int shortFrom = Math.max(0, prior.size() - SHORT_WINDOW);
        Double changeShort = ratio(last.value(), prior.get(shortFrom).value());

        Double z = null;
        if (baseline.size() >= MIN_BASELINE_POINTS) {
            double sum = 0;
            for (Point point : baseline) {
                sum += point.value();
            }
            double mean = sum / baseline.size();
            double sq = 0;
            for (Point point : baseline) {
                sq += (point.value() - mean) * (point.value() - mean);
            }
            double sd = Math.sqrt(sq / (baseline.size() - 1));
            if (sd > 1e-9 * Math.max(1.0, Math.abs(mean))) {
                z = (last.value() - mean) / sd;
            }
        }

        // 基线够长才谈「创新高/新低」：一两个点比出来的极值是噪声
        boolean enough = baseline.size() >= MIN_BASELINE_POINTS;
        double high = baseline.stream().mapToDouble(Point::value).max().orElse(last.value());
        double low = baseline.stream().mapToDouble(Point::value).min().orElse(last.value());
        boolean newHigh = enough && last.value() > high;
        boolean newLow = enough && last.value() < low;

        double magnitude;
        String type;
        if (z != null) {
            magnitude = Math.min(Math.abs(z) / Z_CAP, 1.0);
            type = "zscore";
        } else {
            // 基线不足或恒定：只能用「这一期到底动没动」判断，动过就按最强处理
            magnitude = Math.abs(change1 == null ? 0 : change1) > 1e-9 ? 1.0 : 0;
            type = "change";
        }

        // 绝对变化按数据自身单位算：计数、温度这类用它，不用百分比
        double absolute = Math.abs(last.value() - prior.get(shortFrom).value());

        List<String> flags = new ArrayList<>();
        if (policy.percentMatters() && changeShort != null) {
            flags.add("近 " + SHORT_WINDOW + " 期 " + signedPercent(changeShort));
            if (Math.abs(changeShort) >= policy.bigMove() && policy.bigMove() > 0) {
                flags.add("超过该类「大变化」基准 " + percent(policy.bigMove()));
            }
        } else {
            flags.add("近 " + SHORT_WINDOW + " 期变化 " + trim(absolute));
        }
        if (z != null) {
            flags.add("偏离基线 " + Math.round(Math.abs(z) * 10) / 10.0 + "σ");
        }
        if (newHigh) {
            flags.add("创 " + baseline.size() + " 期新高");
        }
        if (newLow) {
            flags.add("创 " + baseline.size() + " 期新低");
        }

        return new Signal(shape, entity, metric, points.get(0).date(), last.date(), points.size(), windowPoints,
            magnitude, policy.percentMatters() ? changeShort : null, z, newHigh || newLow,
            coverage(points.size(), windowPoints), type, absolute, policy.bigMove(), flags);
    }

    /**
     * 评分：只用来排序，不代表「会爆」。
     * 主信号 45 + 短期变化 20 + 新极值 15 + 栏目权重 20。
     *
     * <p>变化那一项按类别的「大变化」基准归一：宏观 0.3% 与股指 0.3% 不是同一件事。
     */
    public static double score(Signal signal, int columnWeight) {
        if (signal == null) {
            return 0;
        }
        double magnitude = Math.min(signal.magnitude(), 1.0) * 45;
        double change = signal.changePct() == null || signal.changeScale() <= 0 ? 0
            : Math.min(Math.abs(signal.changePct()) / signal.changeScale(), 1.0) * 20;
        double extreme = signal.extreme() ? 15 : 0;
        double column = Math.max(0, Math.min(columnWeight, 30)) / 30.0 * 20;
        return Math.round((magnitude + change + extreme + column) * 100) / 100.0;
    }

    /** 标题只写信号里有的数字，不加工、不推测。 */
    public static String headline(Signal signal, String sourceName, String cadence) {
        if (signal == null) {
            return "";
        }
        String name = sourceName == null || sourceName.isBlank() ? signal.entity() : sourceName;
        StringBuilder text = new StringBuilder(name);
        if (!signal.entity().isBlank()) {
            text.append('·').append(signal.entity());
        }
        if (signal.changePct() != null) {
            text.append(" 近 ").append(SHORT_WINDOW).append(' ').append(unit(cadence)).append(' ')
                .append(signedPercent(signal.changePct()));
        } else if (signal.absChange() > 0) {
            text.append(" 近 ").append(SHORT_WINDOW).append(' ').append(unit(cadence))
                .append(" 变化 ").append(trim(signal.absChange()));
        }
        // 只有知道方向时才说新高低；计数/温度这类没有百分比，不猜方向
        if (signal.extreme() && signal.changePct() != null) {
            text.append(signal.changePct() < 0 ? "，创窗口新低" : "，创窗口新高");
        }
        if (signal.zscore() != null) {
            text.append("（").append(Math.round(Math.abs(signal.zscore()) * 10) / 10.0).append("σ）");
        }
        return text.length() > 200 ? text.substring(0, 200) : text.toString();
    }

    private static String unit(String cadence) {
        return switch (cadence == null ? "" : cadence) {
            case "realtime", "daily" -> "日";
            case "weekly" -> "周";
            case "monthly" -> "月";
            case "annual" -> "年";
            default -> "期";
        };
    }

    private static double coverage(int points, int windowPoints) {
        if (windowPoints <= 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.round(points * 1000.0 / windowPoints) / 1000.0);
    }

    private static Double ratio(double last, double base) {
        if (Math.abs(base) < 1e-12) {
            return null;
        }
        return (last - base) / Math.abs(base);
    }

    private static String percent(double value) {
        return trim(Math.abs(value) * 100) + "%";
    }

    private static String signedPercent(double value) {
        return (value >= 0 ? "+" : "-") + trim(Math.abs(value) * 100) + "%";
    }

    private static String trim(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    private static String text(JsonNode row, int index) {
        if (row == null || !row.isArray() || index >= row.size()) {
            return "";
        }
        JsonNode cell = row.get(index);
        return cell == null || cell.isNull() ? "" : cell.asString("");
    }

    private static Double number(JsonNode row, int index) {
        if (row == null || !row.isArray() || index >= row.size()) {
            return null;
        }
        JsonNode cell = row.get(index);
        if (cell == null || cell.isNull()) {
            return null;
        }
        if (cell.isNumber()) {
            return cell.asDouble();
        }
        try {
            return Double.parseDouble(cell.asString("").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Point(String date, double value) { }

    /**
     * @param magnitude  主信号强度 0–1，跨形态可比
     * @param changePct  窗口累计变化率；rows 为相对变化，算不出时为 null
     * @param zscore     偏离基线的标准差倍数；样本不足或基线恒定时为 null
     * @param coverage   实到点数 / 请求点数
     */
    public record Signal(String shape, String entity, String metric,
                         String periodStart, String periodEnd,
                         int points, int windowPoints,
                         double magnitude, Double changePct, Double zscore,
                         boolean extreme, double coverage, String signalType,
                         double absChange, double changeScale, List<String> flags) {
        public Signal {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }

        /** 信号类型 + 实体 + 窗口，用来在一天内去重。 */
        public String dedupPart() {
            return signalType + "|" + entity + "|" + windowPoints;
        }
    }
}
