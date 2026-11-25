package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L3 审稿的客观层：九条判据里能被机器判定的那部分。
 *
 * <p>这里只做「数得出来」的判断：占位符有没有、路径集合是否相同、实测时长差多少。
 * 「这段值不值得看」这类语言性判断不归这里——归了就是让代码假装懂内容。
 *
 * <p>判据与机器证据的关系：能定论的（hook_3s、early_payoff、mid_progression、visual_sync、
 * style_consistency、no_redundancy、closing_value）由这里出 objective 结论；
 * 定论不了的（peak_focus、pacing_variation）只把机器证据交给模型，结论由模型给。
 */
final class QualityCriteria {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 十条判据，顺序即报告里展示的顺序。 */
    static final List<String> IDS = List.of("hook_3s", "early_payoff", "mid_progression", "peak_focus",
        "visual_sync", "picture_motion", "style_consistency", "pacing_variation", "no_redundancy",
        "closing_value");

    /** 这几条由机器定论，报告里是 objective 条目。 */
    static final Set<String> MACHINE_DECIDED = Set.of("hook_3s", "early_payoff", "mid_progression",
        "visual_sync", "picture_motion", "style_consistency", "no_redundancy", "closing_value");

    /** 机器只提供证据、结论由模型给的两条。 */
    static final Set<String> MODEL_DECIDED = Set.of("peak_focus", "pacing_variation");

    /**
     * 单段超过这个长度即视为节奏上的超长段。
     *
     * <p>唯一消费者是两份稿件契约（{@code NewsScriptContract} / {@code DatasetScriptContract}）：
     * 超长段在写稿阶段就被退回，不用等渲染完钱花出去才发现。
     */
    static final long LONG_SCENE_MS = 12_000L;
    /** 声明时长与实测时长差过这个值即算对不上。 */
    static final long DURATION_TOLERANCE_MS = 2_500L;
    /** 同一个数据路径在这么多段里反复出现即判冗余。 */
    static final int REPEAT_PATH_LIMIT = 3;
    /** 结尾短于这个长度即判结尾空泛。 */
    static final long CLOSING_MIN_MS = 3_000L;
    /** 最长黑场超过这个秒数即判画面没出来。 */
    private static final double BLACK_MAX_SECONDS = 1.0;
    /** 最长静止段超过这个秒数即判画面不动。 */
    private static final double FREEZE_MAX_SECONDS = 3.0;

    private static final Pattern REFERENCE = Pattern.compile("\\{\\{(facts\\.[^{}]+)}}");

    private QualityCriteria() {
    }

    /** 一条机器证据：定位符 + 原文片段。定位符必须能在报告里被复核器解析。 */
    record Evidence(String locator, String quote) {
    }

    /** 机器给出的定论。 */
    record Criterion(String id, String verdict, String severity, boolean repairable,
                      List<Evidence> evidence, String reason) {
    }

    /**
     * 一次审稿的全部输入。只装证据，不装结论。
     *
     * @param script   最终稿 JSON
     * @param dataset  数据看板线快照；解说线为 null
     * @param checks   闸门报告数组
     * @param subtitles 字幕 cue 数组；没有则为空
     * @param focus    每段的画面焦点（按段号索引，1 起）
     */
    record Context(long versionId, String mode, JsonNode script, JsonNode dataset, JsonNode checks,
                   JsonNode subtitles, Map<Integer, DatasetVisualFocus.Focus> focus) {

        JsonNode scenes() {
            return script.path("scenes");
        }

        String title() {
            return script.path("title").asString("");
        }

        String closing() {
            return script.path("closing").asString("");
        }

        /** 第几段（1 起）的文本。 */
        String sceneText(int sceneNo) {
            JsonNode scene = scenes().get(sceneNo - 1);
            if (scene == null) return "";
            return scene.path("lead").asString("") + "。" + scene.path("body").asString("");
        }

        /** 第几段（1 起）引用的 facts 路径集合。 */
        Set<String> sceneRefs(int sceneNo) {
            Set<String> refs = new LinkedHashSet<>();
            Matcher matcher = REFERENCE.matcher(sceneText(sceneNo));
            while (matcher.find()) {
                refs.add(matcher.group(1));
            }
            return refs;
        }

        /** 闸门 measured 里的某个值；没有该闸或没有该键时返回 null。 */
        Double measured(String gate, String key) {
            JsonNode gateNode = gateNode(gate);
            if (gateNode == null) return null;
            JsonNode value = gateNode.path("measured").path(key);
            return value.isNumber() ? value.asDouble() : null;
        }

        JsonNode gateNode(String gate) {
            for (JsonNode item : checks) {
                if (gate.equals(item.path("gate").asString(""))) return item;
            }
            return null;
        }
    }

    /**
     * 计算机器能定论的判据。
     *
     * @return 只包含已定论条目的列表；定论不了的不在这里出现
     */
    static List<Criterion> judge(Context ctx) {
        List<Criterion> out = new ArrayList<>();
        out.add(hook3s(ctx));
        out.add(earlyPayoff(ctx));
        out.add(midProgression(ctx));
        out.add(visualSync(ctx));
        out.add(pictureMotion(ctx));
        out.add(styleConsistency(ctx));
        out.add(noRedundancy(ctx));
        out.add(closingValue(ctx));
        // 「可修」只认定向修复白名单：白名单外的判据即使有结论，也不进修复通道。
        // 报告契约会把这以外的「可修」降级掉，机器自己更不该先标上。
        for (Criterion criterion : out) {
            if (!QualityRepairPlanner.whitelistIds().contains(criterion.id()) && criterion.repairable()) {
                out.set(out.indexOf(criterion), new Criterion(criterion.id(), criterion.verdict(),
                    criterion.severity(), false, criterion.evidence(), criterion.reason()));
            }
        }
        return out;
    }

    /**
     * 开场三秒：有没有把事情说清楚。
     *
     * <p>三件可数的事：首屏是否引用事实、是否把准备过程当内容、首句是否复读标题。
     * 三件都过才是 pass；任一件不成立就是 fail——不是「瑕疵」，是开场没兑现。
     */
    private static Criterion hook3s(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        String lead = ctx.scenes().isEmpty() ? "" : ctx.scenes().get(0).path("lead").asString("");
        String opening = ctx.sceneText(1);
        boolean hasFact = REFERENCE.matcher(opening).find();
        boolean metaOpening = false;
        for (String phrase : DatasetScriptContract.metaOpening()) {
            if (opening.contains(phrase)) {
                metaOpening = true;
                evidence.add(new Evidence("script.scenes[0].lead", phrase));
                break;
            }
        }
        boolean titleRepeat = !ctx.title().isBlank() && lead.contains(ctx.title());
        evidence.add(new Evidence("script.scenes[0].lead", truncate(lead)));
        String verdict = hasFact && !metaOpening && !titleRepeat ? "pass" : "fail";
        String reason = "首屏引用事实=" + hasFact + "，命中开场元话语=" + metaOpening + "，复读标题=" + titleRepeat
            + "；三者全过才算把事情说清楚。";
        return new Criterion("hook_3s", verdict, "fail".equals(verdict) ? "major" : "none",
            "fail".equals(verdict), evidence, reason);
    }

    /**
     * 首屏证据。
     *
     * <p>报告里每条结论都要有能复核的定位符：没有证据的结论读的人没法判断，
     * 只能选择不给结论，而不是给一条空证据。
     */
    private static Evidence openingEvidence(Context ctx) {
        String lead = ctx.scenes().isEmpty() ? "" : ctx.scenes().get(0).path("lead").asString("");
        if (lead.isBlank()) {
            lead = ctx.scenes().isEmpty() ? "" : ctx.scenes().get(0).path("body").asString("");
        }
        if (lead.isBlank()) {
            return null;
        }
        return new Evidence("script.scenes[0].lead", truncate(lead));
    }

    private static void addOpeningEvidence(List<Evidence> evidence, Context ctx) {
        Evidence item = openingEvidence(ctx);
        if (item != null) {
            evidence.add(item);
        }
    }

    /**
     * 前段兑现：观众多快拿到「为什么值得看」。
     *
     * <p>只能粗判到前两屏：字幕对齐是 cue 级，不是词级，假装能算到第几秒说了哪个词就是编的。
     */
    private static Criterion earlyPayoff(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        int early = Math.min(2, ctx.scenes().size());
        int withFact = 0;
        for (int sceneNo = 1; sceneNo <= early; sceneNo++) {
            if (!ctx.sceneRefs(sceneNo).isEmpty()) {
                withFact++;
                evidence.add(new Evidence("script.scenes[" + (sceneNo - 1) + "].lead",
                    truncate(ctx.sceneText(sceneNo))));
            }
        }
        Double start = ctx.measured("subtitles", "scene1StartMs");
        Double end = ctx.measured("subtitles", "scene2EndMs");
        Double earlyMs = start == null || end == null ? null : end - start;
        if (earlyMs != null) {
            evidence.add(new Evidence("checks[subtitles].measured.scene2EndMs", String.valueOf(end.longValue())));
        }
        addOpeningEvidence(evidence, ctx);
        String verdict;
        if (early == 0) {
            verdict = "unknown";
        } else if (withFact == early) {
            verdict = "pass";
        } else if (withFact == 0) {
            verdict = "fail";
        } else {
            verdict = "minor";
        }
        String reason = "前 " + early + " 屏里有事实引用的是 " + withFact + " 屏"
            + (earlyMs == null ? "；前段实测时长未知" : "；前段实测 " + earlyMs.longValue() + " 毫秒")
            + "。只能判到屏级，不做词级推断。";
        return new Criterion("early_payoff", verdict, switch (verdict) {
            case "fail" -> "major";
            case "minor" -> "minor";
            default -> "none";
        }, "fail".equals(verdict), evidence, reason);
    }

    /**
     * 中段推进：相邻两段是不是在说同一件事。
     *
     * <p>机器只能判「引用了完全相同的事实路径」这种硬重复；
     * 「有没有信息增量」是语言判断，交给模型。
     */
    private static Criterion midProgression(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        int scenes = ctx.scenes().size();
        int identical = 0;
        int withFact = 0;
        for (int sceneNo = 1; sceneNo <= scenes; sceneNo++) {
            if (!ctx.sceneRefs(sceneNo).isEmpty()) withFact++;
        }
        for (int sceneNo = 2; sceneNo <= scenes; sceneNo++) {
            Set<String> previous = ctx.sceneRefs(sceneNo - 1);
            Set<String> current = ctx.sceneRefs(sceneNo);
            if (!previous.isEmpty() && previous.equals(current)) {
                identical++;
                evidence.add(new Evidence("script.scenes[" + (sceneNo - 1) + "].body",
                    truncate(ctx.sceneText(sceneNo))));
            }
        }
        addOpeningEvidence(evidence, ctx);
        String verdict;
        if (scenes == 0) {
            verdict = "unknown";
        } else if (identical >= 2 || withFact < 2) {
            verdict = "fail";
        } else if (identical == 1) {
            verdict = "minor";
        } else {
            verdict = "pass";
        }
        String reason = "相邻两段引用了完全相同事实路径的次数=" + identical + "，有事实引用的段数=" + withFact
            + "（至少 2 段）。信息增量本身由模型判断。";
        return new Criterion("mid_progression", verdict, switch (verdict) {
            case "fail" -> "major";
            case "minor" -> "minor";
            default -> "none";
        }, identical > 0, evidence, reason);
    }

    /**
     * 画面与旁白配不配得上。
     *
     * <p>画面焦点由同一份稿子推出来，比对必然一致——所以这条只判「引用能不能解析」
     * 与「图表能不能渲染」，不假装能查出画面讲了另一件事。
     */
    private static Criterion visualSync(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        if (ctx.dataset() == null) {
            // 解说线没有事实集，画面焦点无从校验：不猜，交给 unknown。
            return new Criterion("visual_sync", "unknown", "none", false, evidence,
                "本条没有事实集，无法校验画面焦点与旁白的对应关系。");
        }
        boolean resolvable = true;
        int parsed = 0;
        for (int sceneNo = 1; sceneNo <= ctx.scenes().size(); sceneNo++) {
            DatasetVisualFocus.Focus focus = ctx.focus().get(sceneNo);
            if (focus == null) continue;
            parsed++;
            for (String ref : focus.refs()) {
                if (ctx.dataset() == null || DatasetFacts.at(ctx.dataset(), "facts." + ref) == null) {
                    resolvable = false;
                    evidence.add(new Evidence("focus[scene" + sceneNo + "].refs", ref));
                }
            }
        }
        String chart = ctx.script().path("chart").asString("");
        boolean renderable = !chart.isBlank();
        if (renderable) {
            evidence.add(new Evidence("script.chart", chart));
        } else {
            addOpeningEvidence(evidence, ctx);
        }
        String verdict = resolvable && renderable ? "pass" : "fail";
        String reason = "可解析画面焦点的段数=" + parsed + "，焦点引用全部可解析=" + resolvable
            + "，图表类型=" + (chart.isBlank() ? "（无）" : chart) + "。";
        return new Criterion("visual_sync", verdict, "fail".equals(verdict) ? "major" : "none",
            false, evidence, reason);
    }

    /**
     * 画面到底有没有在动。
     *
     * <p>机器定论：黑场说明画面没出来，冻结段说明画面不动。两者都是「像 PPT」的直接来源——
     * 版式再花，画面一停，观众看到的就是一张静止的图配一段念稿。
     *
     * <p>检不出来就交 unknown，不猜：没有画面信号检测结果时不能说「画面在动」。
     */
    private static Criterion pictureMotion(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        Double black = ctx.measured("video_signal", "longestBlackSeconds");
        Double freeze = ctx.measured("video_signal", "longestFreezeSeconds");
        if (black == null || freeze == null) {
            return new Criterion("picture_motion", "unknown", "none", false, evidence,
                "没有画面信号检测结果，无法判断画面有没有在动。");
        }
        evidence.add(new Evidence("checks[video_signal].measured.longestBlackSeconds", String.valueOf(black)));
        evidence.add(new Evidence("checks[video_signal].measured.longestFreezeSeconds", String.valueOf(freeze)));
        boolean blackOk = black <= BLACK_MAX_SECONDS;
        boolean freezeOk = freeze <= FREEZE_MAX_SECONDS;
        String verdict = blackOk && freezeOk ? "pass" : "fail";
        String reason = "最长黑场 " + black + " 秒（上限 " + BLACK_MAX_SECONDS + " 秒），最长静止段 "
            + freeze + " 秒（上限 " + FREEZE_MAX_SECONDS + " 秒）。";
        return new Criterion("picture_motion", verdict, "fail".equals(verdict) ? "major" : "none",
            false, evidence, reason);
    }

    /**
     * 视觉风格是否统一。
     *
     * <p>只看这一条片子内部：版式检查有没有错、联络表有没有空白格、锁定资产是否一致。
     * 「这个风格适不适合这个题材」是人工选题判断，不进这里。
     */
    private static Criterion styleConsistency(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        double errors = orZero(ctx.measured("visual", "checkErrors"));
        double warnings = orZero(ctx.measured("visual", "checkWarnings"));
        double blank = orZero(ctx.measured("visual", "blankFrames"));
        evidence.add(new Evidence("checks[visual].measured.checkErrors", String.valueOf((long) errors)));
        evidence.add(new Evidence("checks[visual].measured.blankFrames", String.valueOf((long) blank)));
        String verdict;
        if (errors > 0 || blank > 0) {
            verdict = "fail";
        } else if (warnings > 0) {
            verdict = "minor";
        } else {
            verdict = "pass";
        }
        String reason = "版式错误=" + (long) errors + "，警告=" + (long) warnings + "，联络表空白格=" + (long) blank
            + "。只判本片内部是否统一。";
        return new Criterion("style_consistency", verdict, switch (verdict) {
            case "fail" -> "major";
            case "minor" -> "minor";
            default -> "none";
        }, false, evidence, reason);
    }

    /**
     * 有没有冗余画面。
     *
     * <p>同一个数据路径在三段以上反复出现，或相邻两段的画面焦点完全一样，都算冗余。
     * 与 mid_progression 的分界：那条判叙述重复，这条判画面重复。
     */
    private static Criterion noRedundancy(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int sceneNo = 1; sceneNo <= ctx.scenes().size(); sceneNo++) {
            for (String ref : ctx.sceneRefs(sceneNo)) {
                counts.merge(ref, 1, Integer::sum);
            }
        }
        int repeat = 0;
        String repeated = "";
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= REPEAT_PATH_LIMIT) {
                repeat++;
                repeated = entry.getKey();
                evidence.add(new Evidence("script.scenes[].lead", entry.getKey() + " 出现 " + entry.getValue() + " 次"));
            }
        }
        int sameFocus = 0;
        for (int sceneNo = 2; sceneNo <= ctx.scenes().size(); sceneNo++) {
            DatasetVisualFocus.Focus previous = ctx.focus().get(sceneNo - 1);
            DatasetVisualFocus.Focus current = ctx.focus().get(sceneNo);
            if (previous != null && current != null && previous.mode().equals(current.mode())
                && previous.refs().equals(current.refs())) {
                sameFocus++;
            }
        }
        addOpeningEvidence(evidence, ctx);
        String verdict;
        if (repeat > 0 || sameFocus >= 2) {
            verdict = "fail";
        } else if (sameFocus == 1) {
            verdict = "minor";
        } else {
            verdict = "pass";
        }
        String reason = "反复出现的数据路径数=" + repeat + (repeated.isEmpty() ? "" : "（例如 " + repeated + "）")
            + "，相邻两段画面焦点完全相同的次数=" + sameFocus + "。";
        return new Criterion("no_redundancy", verdict, switch (verdict) {
            case "fail" -> "minor";
            case "minor" -> "minor";
            default -> "none";
        }, sameFocus > 0, evidence, reason);
    }

    /**
     * 结尾值不值得开口。
     *
     * <p>机器只判两件：是不是空关注引导、结尾有没有时长。
     * 「观众答不答得上」交给模型。
     */
    private static Criterion closingValue(Context ctx) {
        List<Evidence> evidence = new ArrayList<>();
        String closing = ctx.closing();
        boolean empty = false;
        for (String phrase : DatasetScriptContract.emptyClosing()) {
            if (closing.contains(phrase)) {
                empty = true;
                evidence.add(new Evidence("script.closing", phrase));
                break;
            }
        }
        Double start = ctx.measured("subtitles", "closingStartMs");
        Double end = ctx.measured("subtitles", "closingEndMs");
        Double closingMs = start == null || end == null ? null : end - start;
        if (closingMs != null) {
            evidence.add(new Evidence("checks[subtitles].measured.closingEndMs", String.valueOf(end.longValue())));
        }
        if (!ctx.closing().isBlank()) {
            evidence.add(new Evidence("script.closing", truncate(ctx.closing())));
        }
        String verdict;
        if (empty) {
            verdict = "fail";
        } else if (closingMs != null && closingMs < CLOSING_MIN_MS) {
            verdict = "minor";
        } else {
            verdict = "pass";
        }
        String reason = "命中空泛关注引导=" + empty
            + (closingMs == null ? "；结尾实测时长未知" : "；结尾实测 " + closingMs.longValue() + " 毫秒")
            + "。是否值得观众开口由模型判断。";
        return new Criterion("closing_value", verdict, switch (verdict) {
            case "fail" -> "major";
            case "minor" -> "minor";
            default -> "none";
        }, empty, evidence, reason);
    }

    /**
     * 给模型的两条判据准备机器证据。
     *
     * <p>peak_focus 看各段的强信号分布；pacing_variation 看各段实测时长与声明时长的差。
     */
    static Map<String, List<Evidence>> modelEvidence(Context ctx) {
        Map<String, List<Evidence>> out = new LinkedHashMap<>();

        List<Evidence> peak = new ArrayList<>();
        for (int sceneNo = 1; sceneNo <= ctx.scenes().size(); sceneNo++) {
            DatasetVisualFocus.Focus focus = ctx.focus().get(sceneNo);
            if (focus == null) continue;
            peak.add(new Evidence("focus[scene" + sceneNo + "].mode", focus.mode()));
            peak.add(new Evidence("focus[scene" + sceneNo + "].refs", String.join(",", focus.refs())));
        }
        out.put("peak_focus", peak);

        List<Evidence> pacing = new ArrayList<>();
        int scenes = ctx.scenes().size();
        for (int sceneNo = 1; sceneNo <= scenes; sceneNo++) {
            Double start = ctx.measured("subtitles", "scene" + sceneNo + "StartMs");
            Double end = ctx.measured("subtitles", "scene" + sceneNo + "EndMs");
            if (start == null || end == null) continue;
            long measured = (long) (end - start);
            pacing.add(new Evidence("checks[subtitles].measured.scene" + sceneNo + "EndMs",
                String.valueOf(measured)));
            double declared = ctx.scenes().get(sceneNo - 1).path("duration_s").asDouble(0);
            if (declared > 0) {
                long diff = Math.abs((long) (declared * 1000) - measured);
                if (diff > DURATION_TOLERANCE_MS) {
                    pacing.add(new Evidence("script.scenes[" + (sceneNo - 1) + "].duration_s",
                        "声明 " + (long) declared + " 秒，实测 " + measured + " 毫秒"));
                }
            }
        }
        out.put("pacing_variation", pacing);
        return out;
    }

    /** 节奏判据的机器事实，供模型判断时参考。 */
    record PacingFacts(long longestMs, long shortestMs, double ratio, long longestSceneNo,
                       long declaredMismatchCount) {
    }

    /** 汇总节奏事实：最长段、最短段、比值、声明与实测对不上的段数。 */
    static PacingFacts pacingFacts(Context ctx) {
        long longest = 0L;
        long shortest = Long.MAX_VALUE;
        int longestScene = 0;
        int mismatch = 0;
        int scenes = ctx.scenes().size();
        for (int sceneNo = 1; sceneNo <= scenes; sceneNo++) {
            Double start = ctx.measured("subtitles", "scene" + sceneNo + "StartMs");
            Double end = ctx.measured("subtitles", "scene" + sceneNo + "EndMs");
            if (start == null || end == null) continue;
            long measured = (long) (end - start);
            if (measured > longest) {
                longest = measured;
                longestScene = sceneNo;
            }
            shortest = Math.min(shortest, measured);
            double declared = ctx.scenes().get(sceneNo - 1).path("duration_s").asDouble(0);
            if (declared > 0 && Math.abs((long) (declared * 1000) - measured) > DURATION_TOLERANCE_MS) {
                mismatch++;
            }
        }
        if (shortest == Long.MAX_VALUE) {
            shortest = 0L;
        }
        double ratio = shortest <= 0 ? 0d : (double) longest / shortest;
        return new PacingFacts(longest, shortest, ratio, longestScene, mismatch);
    }

    private static double orZero(Double value) {
        return value == null ? 0d : value;
    }

    private static String truncate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 120 ? value : value.substring(0, 120);
    }
}
