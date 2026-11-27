package org.dromara.heron.service.impl.shared.production;

import org.dromara.heron.domain.EditorialStory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本与策划结论的对位校验。
 *
 * <p>story/1 定的结构是：开场讲张力 → 中段一条主张一段 → 结尾讲落点。
 * 这里就按这三条位置关系核脚本，核的是「位置对不对、引用的路径在不在信封里」，
 * 不核文采好不好——好不好交给审稿模型。
 *
 * <p>四条检查：
 * <ol>
 *   <li>第 1 段（开场）引用的路径必须都在张力的出处里；</li>
 *   <li>中段段数不少于事实主张条数，且每条事实主张的出处都得在中段出现过；</li>
 *   <li>结尾至少引用一条落点的出处；</li>
 *   <li>全文引用的路径都在「故事信封 ∪ 标题用到的路径」之内。</li>
 * </ol>
 */
public final class StoryScriptAlignment {

    /** 只认 facts 下的占位符；趋势线的 {{trend.*}} 不在这里。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(facts\\.[^{}]+)}}");

    private StoryScriptAlignment() {
    }

    /**
     * 脚本与故事是否对得上。
     *
     * @param script 已解析的脚本 JSON
     * @param story  策划结论；为 null 表示这次生产没有故事，不做对位校验
     * @param title  选中的标题原文，用来把标题用到的路径并进信封
     */
    public static List<String> violations(JsonNode script, EditorialStory story, String title) {
        List<String> items = new ArrayList<>();
        if (story == null || script == null || !script.isObject()) {
            return items;
        }
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            // 形状问题归脚本契约，这里不重复报
            return items;
        }

        Set<String> envelope = new LinkedHashSet<>(story.allEvidenceRefs());
        envelope.addAll(factPaths(title));

        // 一、开场 ↔ 张力
        EditorialStory.Tension tension = story.tension();
        Set<String> openingPaths = scenePaths(scenes.get(0));
        if (tension == null || tension.evidenceRefs().isEmpty()) {
            items.add("故事没有张力出处，开场无从兑现");
        } else if (openingPaths.isEmpty()) {
            items.add("开场没有引用任何事实路径，张力没有兑现");
        } else {
            for (String path : openingPaths) {
                if (!tension.evidenceRefs().contains(path)) {
                    items.add("开场引用了张力之外的路径：" + path);
                }
            }
        }

        // 二、中段 ↔ 主张
        List<EditorialStory.Claim> facts = story.factClaims();
        int middle = Math.max(0, scenes.size() - 2);
        if (middle < facts.size()) {
            items.add("中段只有 " + middle + " 段，讲不完 " + facts.size() + " 条事实主张");
        }
        Set<String> middlePaths = new LinkedHashSet<>();
        for (int index = 1; index <= scenes.size() - 2; index++) {
            middlePaths.addAll(scenePaths(scenes.get(index)));
        }
        for (EditorialStory.Claim claim : facts) {
            boolean covered = claim.evidenceRefs().stream().anyMatch(middlePaths::contains);
            if (!covered) {
                items.add("事实主张 " + claim.id() + " 的出处没有出现在中段");
            }
        }

        // 三、结尾 ↔ 落点
        EditorialStory.Payoff payoff = story.payoff();
        Set<String> closingPaths = factPaths(script.path("closing").asString(""));
        if (payoff == null || payoff.evidenceRefs().isEmpty()) {
            items.add("故事没有落点出处");
        } else if (closingPaths.stream().noneMatch(payoff.evidenceRefs()::contains)) {
            items.add("结尾没有引用落点的任何路径");
        }

        // 四、全文信封（每一段都要核，最后一段也算正文）
        Set<String> all = new LinkedHashSet<>();
        for (JsonNode scene : scenes) {
            all.addAll(scenePaths(scene));
        }
        all.addAll(closingPaths);
        for (String path : all) {
            if (!envelope.contains(path)) {
                items.add("正文引用了故事信封之外的路径：" + path);
            }
        }
        return List.copyOf(new LinkedHashSet<>(items));
    }

    /** 一段里引用到的所有事实路径。 */
    private static Set<String> scenePaths(JsonNode scene) {
        if (scene == null || !scene.isObject()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        result.addAll(factPaths(scene.path("lead").asString("")));
        result.addAll(factPaths(scene.path("body").asString("")));
        return result;
    }

    /** 一段文本里引用到的所有事实路径。标题与正文都用它，标题的路径要并进信封。 */
    public static Set<String> factPaths(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }
}
