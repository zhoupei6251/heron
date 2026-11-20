package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 趋势证据审核：把「判定」和「稿子说了什么」放在一起核对。
 *
 * <p>趋势线的三条质量红线不是写在提示词里就算数，得有机器的检查：
 * ① 不得把区间波动说成长期趋势 —— 判定是区间波动/样本不足时，稿子里不许出现「持续上行」这类断言；
 * ② 不得把推测写成确定事实 —— 预计/将会/必然之类的词一律挡下；
 * ③ 关键观点必须可追溯到具体证据 —— 稿子必须引用区间口径（首末日期），否则「十二期」这种说法没有任何出处。
 *
 * <p>这里只做一致性核对，不重算判定：判定由 {@link TrendAnalysis} 从快照算出来并随 trend/1 落库，
 * 闸只核对它自洽、且与快照的哈希对得上。
 */
public final class TrendContract {

    public static final String SCHEMA_VERSION = "trend/1";

    static final Set<String> VERDICTS = Set.of(TrendAnalysis.SUSTAINED_UP, TrendAnalysis.SUSTAINED_DOWN,
        TrendAnalysis.RANGE, TrendAnalysis.INSUFFICIENT);

    /** 明确单边的说法：只有 sustained_up 才能用。 */
    static final Set<String> UP_WORDS = Set.of("持续上行", "持续上涨", "一路上涨", "一路走高", "不断走高",
        "不断上涨", "稳步上升", "稳步上涨", "单边上行", "单边上涨", "逐期走高", "屡创新高");
    /** 明确单边的说法：只有 sustained_down 才能用。 */
    static final Set<String> DOWN_WORDS = Set.of("持续下行", "持续下跌", "一路下跌", "一路走低", "不断走低",
        "不断下跌", "稳步下降", "稳步下跌", "单边下行", "单边下跌", "逐期走低", "跌跌不休");
    /** 方向词：样本不足时连方向都不许给。 */
    static final Set<String> DIRECTION_WORDS = Set.of("趋势", "上涨", "下跌", "走高", "走低", "攀升", "回落", "反弹");
    /** 把推测写成确定事实的说法。 */
    static final Set<String> SPECULATION_WORDS = Set.of("预计", "将会", "有望", "必然", "必将会", "一定会", "肯定会",
        "毫无疑问", "必将", "大概率", "不出意外", "板上钉钉");

    private TrendContract() { }

    public static List<String> violations(JsonNode trend, JsonNode script, JsonNode dataset) {
        List<String> out = new ArrayList<>();
        out.addAll(trendViolations(trend, dataset));
        if (!out.isEmpty()) {
            // 判定本身坏掉了，「稿子跟判定一致」就没法谈；先让调用方去修判定
            return out;
        }
        String text = textOf(script);
        String verdict = trend.path("verdict").asString("");
        if (TrendAnalysis.INSUFFICIENT.equals(verdict)) {
            hit(text, DIRECTION_WORDS).forEach(word -> out.add(
                "样本不足（" + trend.path("points").asInt(0) + " 期）却给了方向说法：" + word));
        }
        if (TrendAnalysis.RANGE.equals(verdict)) {
            hit(text, UP_WORDS).forEach(word -> out.add("判定是区间波动，旁白却说成上行：" + word));
            hit(text, DOWN_WORDS).forEach(word -> out.add("判定是区间波动，旁白却说成下行：" + word));
        }
        if (TrendAnalysis.SUSTAINED_UP.equals(verdict)) {
            hit(text, DOWN_WORDS).forEach(word -> out.add("判定是持续上行，旁白却说成下行：" + word));
        }
        if (TrendAnalysis.SUSTAINED_DOWN.equals(verdict)) {
            hit(text, UP_WORDS).forEach(word -> out.add("判定是持续下行，旁白却说成上行：" + word));
        }
        hit(text, SPECULATION_WORDS).forEach(word -> out.add(
            "旁白把推测写成了确定事实：" + word + "（原因与走势只能作为待验证的推测，不能这样断言）"));
        if (!anchored(text)) {
            out.add("旁白没有引用区间口径：关键观点必须写成 {{facts.first.date}} 到 {{facts.last.date}}，"
                + "否则「这几期」的说法没有出处");
        }
        if ("divergent".equals(trend.path("corroboration").asString(""))
            && (TrendAnalysis.directional(trend))) {
            out.add("多来源交叉核对出现分歧，却给了单一方向结论：分歧来源见 trend.disagreements");
        }
        return out;
    }

    /** 判定自身的自洽性：版本、取值、证据链、阈值与结论对不对得上。 */
    static List<String> trendViolations(JsonNode trend, JsonNode dataset) {
        List<String> out = new ArrayList<>();
        if (trend == null || !trend.isObject()) {
            return List.of("缺少趋势判定（trend/1）");
        }
        if (!SCHEMA_VERSION.equals(trend.path("contract").asString(""))) {
            out.add("趋势判定契约版本不是 " + SCHEMA_VERSION);
        }
        String verdict = trend.path("verdict").asString("");
        if (!VERDICTS.contains(verdict)) {
            out.add("趋势判定取值不合法：" + verdict);
        }
        if (trend.path("evidence").isArray() && trend.path("evidence").isEmpty()) {
            out.add("趋势判定没有证据链（evidence 为空）");
        }
        JsonNode primary = trend.path("evidence").isArray() && !trend.path("evidence").isEmpty()
            ? trend.path("evidence").get(0) : null;
        if (primary == null) {
            out.add("趋势判定缺少主来源证据");
        } else {
            String expected = dataset == null ? "" : dataset.path("data_sha256").asString("");
            String actual = primary.path("data_sha256").asString("");
            if (!expected.isBlank() && !expected.equals(actual)) {
                out.add("趋势证据与快照哈希不一致：判定用的不是这一份数据");
            }
        }
        if (TrendAnalysis.directional(trend)) {
            double agreement = trend.path("sign_agreement").asDouble(0);
            if (agreement < TrendAnalysis.SIGN_AGREEMENT) {
                out.add("同向点对占比 " + agreement + " 低于 " + TrendAnalysis.SIGN_AGREEMENT
                    + "，却给出了单边判定");
            }
        }
        if (TrendAnalysis.INSUFFICIENT.equals(verdict) && !trend.path("segments").isEmpty()) {
            out.add("样本不足的判定不该带分段结论");
        }
        return out;
    }

    /** 区间口径的锚点：首末日期或年份，任一出现即视为可追溯。 */
    static boolean anchored(String text) {
        return text.contains("{{facts.first.date}}") || text.contains("{{facts.last.date}}")
            || text.contains("{{facts.period.") || text.contains("{{facts.first.value}}")
            || text.contains("{{facts.last.value}}");
    }

    private static Set<String> hit(String text, Set<String> words) {
        Set<String> found = new LinkedHashSet<>();
        for (String word : words) {
            if (text.contains(word)) {
                found.add(word);
            }
        }
        return found;
    }

    /** 旁白与标题的全部文本：标题里的一句断言比正文里的一句更严重。 */
    static String textOf(JsonNode script) {
        if (script == null || !script.isObject()) {
            return "";
        }
        StringBuilder text = new StringBuilder(script.path("title").asString(""))
            .append('\n').append(script.path("closing").asString(""));
        JsonNode scenes = script.path("scenes");
        if (scenes.isArray()) {
            for (JsonNode scene : scenes) {
                text.append('\n').append(scene.path("lead").asString(""))
                    .append('\n').append(scene.path("body").asString(""));
            }
        }
        return text.toString();
    }
}
