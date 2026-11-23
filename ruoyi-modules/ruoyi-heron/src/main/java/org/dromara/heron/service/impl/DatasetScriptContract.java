package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据看板脚本契约：形状归这里判，数字与占位符归 DatasetContract 判。
 * 与文章稿分开，因为出处是数据集而不是正文。
 */
public final class DatasetScriptContract {

    private static final Set<String> FIELDS = Set.of("dataset_sha256", "source_key", "title", "scenes", "closing",
        "source_name", "chart");
    /**
     * 段落字段。scene_type / beat / on_screen_text 是分镜三件套：
     * 这一屏用什么画面、承担什么叙事节点、上屏写哪句话。形状在契约层看，
     * 取值是否成立由 StoryboardContract 判（它要等模板声明的组件能力才知道）。
     */
    private static final Set<String> SCENE_FIELDS = Set.of("lead", "body", "duration_s",
        "scene_type", "beat", "on_screen_text");
    /**
     * 渲染端真正实现了的图表模块，取值必须与 data/templates/_gen/behaviors/chart.js 的白名单一致。
     * 契约只映射到这张表里的值：纸上写过但没实现的图表类型不允许放行。
     */
    private static final Set<String> RENDERABLE_CHARTS = Set.of("line", "bars", "race", "stacked", "multiline",
        "radar", "globe");
    private static final int TITLE_CODE_POINTS = 15;
    private static final int MIN_SCENES = 3;
    private static final int MAX_SCENES = 6;
    private static final int MIN_TOTAL_SECONDS = 30;
    private static final int MAX_TOTAL_SECONDS = 75;
    private static final Pattern PERIOD_CHANGE = Pattern.compile("\\{\\{facts\\.change(?:_pct)?}}");
    private static final Pattern DAILY_CHANGE = Pattern.compile(
        "(?:较|比)(?:前一日|上一日|昨日|昨天|上一交易日|前一交易日)|单日(?:上涨|下跌|涨幅|跌幅|变化|涨跌)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{facts\\.([^{}]+)}}");
    /** 开场先念来源、口径、栏目属于把准备过程当内容，观众第一秒就该拿到结论或反差。 */
    private static final List<String> META_OPENING = List.of(
        "数据源", "数据快照", "数据来自", "来源于", "据统计", "统计口径", "根据数据", "本栏目", "本期节目",
        "如图所示", "下面我们来看", "接下来我们看");
    /** 空洞的关注引导，对观众没有信息价值，也不解释这条片子的结论。 */
    private static final List<String> EMPTY_CLOSING = List.of(
        "持续关注", "欢迎关注", "记得关注", "点个关注", "点点关注", "加个关注", "感谢观看", "感谢收看",
        "下期再见", "我们下期", "敬请期待", "点赞收藏");
    /** 至少两段要引用真实 facts，否则整条片子只剩空泛形容。 */
    private static final int MIN_FACT_SCENES = 2;

    /**
     * 开场元话语词表。L3 审稿要判「开场有没有把准备过程当内容」，
     * 词表只有一份，避免契约层与审稿层各写一份然后漂移。
     */
    static List<String> metaOpening() {
        return META_OPENING;
    }

    /** 空泛关注引导词表。L3 的结尾判据复用同一份。 */
    static List<String> emptyClosing() {
        return EMPTY_CLOSING;
    }

    private DatasetScriptContract() { }

    /**
     * 数据形态 → 图表模块；模板侧按这个值选绘图区。
     *
     * <p>{@code geo} 映射到 {@code globe}：三维地球由 3D 模板实现，2D 模板没有这个模块。
     * 选线内编辑按 {@link VisualMode} 把 geo 故事收窄到 3D 模板，
     * 所以 {@code chart=globe} 不会落进 2D 模板——配对由选模板那一层保证，不靠渲染期碰运气。
     * 空串仍表示「这个形态没有可用图表」，由取数闸在调模型之前拒绝。
     */
    public static String chartFor(String shape) {
        return switch (shape == null ? "" : shape) {
            case "wide" -> "line";
            case "long" -> "race";
            case "rows" -> "bars";
            case "geo" -> "globe";
            default -> "";
        };
    }

    /** 该形态当前能否出图；不能出图的形态不许进生产。 */
    public static boolean renderable(String shape) {
        return RENDERABLE_CHARTS.contains(chartFor(shape));
    }

    public static List<String> violations(JsonNode script, JsonNode snapshot) {
        if (script == null || !script.isObject()) return List.of("脚本不是 JSON 对象");
        List<String> items = new ArrayList<>();
        for (String field : List.of("dataset_sha256", "source_key", "title", "scenes", "closing", "source_name",
            "chart")) {
            if (!script.has(field)) items.add("缺字段 " + field);
        }
        for (String field : script.propertyNames()) {
            if (!FIELDS.contains(field)) items.add("未知字段 " + field);
        }
        String expectedSha = snapshot.path("data_sha256").asString("");
        if (!expectedSha.equals(script.path("dataset_sha256").asString(""))) {
            items.add("dataset_sha256 与快照不一致");
        }
        String expectedKey = snapshot.path("source_key").asString("");
        if (!expectedKey.equals(script.path("source_key").asString(""))) {
            items.add("source_key 与快照不一致");
        }
        String shape = snapshot.path("shape").asString("");
        String expectedChart = chartFor(shape);
        if (!RENDERABLE_CHARTS.contains(expectedChart)) {
            items.add("数据形态 " + shape + " 没有可渲染的图表模块");
        } else if (!expectedChart.equals(script.path("chart").asString(""))) {
            items.add("chart 与数据形态不一致，应为 " + expectedChart);
        }
        title(items, script, snapshot);
        text(items, script, "closing", 200);
        text(items, script, "source_name", 80);
        scenes(items, script);
        narration(items, script, snapshot);
        periodMeaning(items, script, snapshot);
        narrative(items, script, snapshot);
        return List.copyOf(new java.util.LinkedHashSet<>(items));
    }

    /**
     * 叙事结构：只判「有没有故事」的硬性下限，不判故事好不好。
     *
     * <p>四个可机器判定的点，都是退稿级的：
     * 开场别先讲来源口径、开场要有具体信息、别把标题原句搬进第一段、结尾不能是空关注引导。
     * 更难的内容质量（角度是否成立、是否有真正信息增量）交给审稿模型，不在这里假装客观。
     */
    private static void narrative(List<String> items, JsonNode script, JsonNode snapshot) {
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) return;
        JsonNode first = scenes.get(0);
        String opening = (first.path("lead").asString("") + " " + first.path("body").asString("")).strip();
        for (String phrase : META_OPENING) {
            if (opening.contains(phrase)) {
                items.add("开场先从来源或口径讲起（含「" + phrase + "」），应从结论或反差切入");
                break;
            }
        }
        // 前两屏就要兑现具体信息：只用第一屏卡死会误杀「用问题开场」的好稿，
        // 放到前两屏既守住兑现速度，又不替模型规定第一句必须带数字。
        int early = Math.min(2, scenes.size());
        boolean earlyFact = false;
        for (int index = 0; index < early; index++) {
            JsonNode scene = scenes.get(index);
            String text = scene.path("lead").asString("") + scene.path("body").asString("");
            if (PLACEHOLDER.matcher(text).find()) earlyFact = true;
        }
        if (!earlyFact) {
            items.add("前两屏没有任何数据事实，观众拿不到可验证的具体信息");
        }
        String title = script.path("title").asString("");
        String firstLead = first.path("lead").asString("");
        if (!title.isBlank() && !firstLead.isBlank() && firstLead.contains(title)) {
            items.add("开场口播与标题逐字重复，标题与开场应互相配合而不是复读");
        }
        String closing = script.path("closing").asString("");
        for (String phrase : EMPTY_CLOSING) {
            if (closing.contains(phrase)) {
                items.add("结尾是空泛的关注引导（含「" + phrase + "」），应总结结论或提出有价值的问题");
                break;
            }
        }
        int factScenes = 0;
        for (JsonNode scene : scenes) {
            String text = scene.path("lead").asString("") + scene.path("body").asString("");
            if (PLACEHOLDER.matcher(text).find()) factScenes++;
        }
        if (factScenes < MIN_FACT_SCENES) {
            items.add("全文只有 " + factScenes + " 段引用数据事实，至少 " + MIN_FACT_SCENES + " 段要有");
        }
    }

    private static void scenes(List<String> items, JsonNode script) {
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray()) {
            items.add("scenes 不是数组");
            return;
        }
        if (scenes.size() < MIN_SCENES || scenes.size() > MAX_SCENES) {
            items.add("段落数 " + scenes.size() + " 不在 " + MIN_SCENES + "–" + MAX_SCENES);
        }
        String title = script.path("title").asString("");
        double total = 0;
        for (int index = 0; index < scenes.size(); index++) {
            JsonNode scene = scenes.get(index);
            if (!scene.isObject()) {
                items.add("第 " + (index + 1) + " 段不是对象");
                continue;
            }
            for (String field : scene.propertyNames()) {
                if (!SCENE_FIELDS.contains(field)) items.add("第 " + (index + 1) + " 段有未知字段 " + field);
            }
            text(items, scene, "lead", 20);
            text(items, scene, "body", 70);
            JsonNode duration = scene.path("duration_s");
            if (!duration.isNumber() || duration.asDouble() <= 0) {
                items.add("第 " + (index + 1) + " 段缺少正数 duration_s");
            } else {
                total += duration.asDouble();
            }
            if (!title.isBlank()
                && (title.equals(scene.path("lead").asString("")) || title.equals(scene.path("body").asString("")))) {
                items.add("第 " + (index + 1) + " 段与标题重复");
            }
        }
        if (total > 0 && (total < MIN_TOTAL_SECONDS || total > MAX_TOTAL_SECONDS)) {
            items.add("总时长 " + (int) total + " 秒不在 " + MIN_TOTAL_SECONDS + "–" + MAX_TOTAL_SECONDS + " 秒");
        }
    }

    private static void narration(List<String> items, JsonNode script, JsonNode snapshot) {
        StringBuilder text = new StringBuilder();
        for (JsonNode scene : script.path("scenes")) {
            text.append(scene.path("lead").asString("")).append('。')
                .append(scene.path("body").asString("")).append('。');
        }
        text.append(script.path("closing").asString(""));
        for (String violation : DatasetContract.narrationViolations(snapshot, text.toString())) {
            items.add("旁白：" + violation);
        }
    }

    /** 多点序列的 change 是首末区间变化，不能借它声称单日涨跌。 */
    private static void periodMeaning(List<String> items, JsonNode script, JsonNode snapshot) {
        if (!"wide".equals(snapshot.path("shape").asString("")) || snapshot.path("rows").size() <= 2) {
            return;
        }
        List<String> passages = new ArrayList<>();
        for (JsonNode scene : script.path("scenes")) {
            passages.add(scene.path("lead").asString("") + "。" + scene.path("body").asString(""));
        }
        passages.add(script.path("closing").asString(""));
        for (int index = 0; index < passages.size(); index++) {
            String text = passages.get(index);
            if (PERIOD_CHANGE.matcher(text).find() && DAILY_CHANGE.matcher(text).find()) {
                String locator = index == passages.size() - 1 ? "结尾" : "第 " + (index + 1) + " 段";
                items.add("旁白：" + locator + "把区间变化描述为单日变化，请使用区间起止口径");
            }
        }
    }

    /** 标题允许占位符，但上限按解析后的字数算，避免“占位符很长、上屏很短”绕过限制。 */
    private static void title(List<String> items, JsonNode script, JsonNode snapshot) {
        String value = script.path("title").asString("");
        if (value.isBlank()) {
            items.add("title 为空");
            return;
        }
        int precision = snapshot.path("metric").path("precision").asInt(2);
        String resolved;
        try {
            resolved = DatasetPlaceholders.fill(value, snapshot, precision);
        } catch (RuntimeException e) {
            items.add("title 占位符无法解析");
            return;
        }
        int length = resolved.codePointCount(0, resolved.length());
        if (length > TITLE_CODE_POINTS) {
            items.add("title " + length + " 字，超过上限 " + TITLE_CODE_POINTS + " 字");
        }
    }

    private static void text(List<String> items, JsonNode node, String field, int maxCodePoints) {
        if (!node.has(field)) return;
        String value = node.path(field).asString("");
        int length = value.codePointCount(0, value.length());
        if (value.isBlank()) items.add(field + " 为空");
        else if (length > maxCodePoints) items.add(field + " " + length + " 字，超过上限 " + maxCodePoints + " 字");
    }
}
