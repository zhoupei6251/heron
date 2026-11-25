package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据看板故事计划契约：v2 脚本内冻结一份先于旁白产生的计划。
 *
 * <p>计划按屏幕顺序与 {@code scenes} 对位。脚本必须使用计划声明的证据、叙事节点与画面类型；
 * 模型不能在写旁白时偷偷换故事。v1（没有 {@code schema_version} 或声明为 1）不检查计划。
 */
public final class StoryPlanContract {

    static final String SCHEMA_VERSION = "story-plan/1";
    private static final Set<String> PLAN_FIELDS = Set.of("schemaVersion", "datasetSha256", "hookType",
        "coreThesis", "scenes");
    private static final Set<String> SCENE_FIELDS = Set.of("purpose", "evidenceRefs", "beat", "emotion",
        "rhythm", "expectedVisual");
    private static final Set<String> VISUAL_FIELDS = Set.of("sceneType", "description");
    /** 数据看板模板实际支持的画面类型，与 script-contract.md 一致。 */
    private static final Set<String> SCENE_TYPES = Set.of("headline", "text", "chart", "number", "compare", "timeline");
    private static final Set<String> EMOTIONS = Set.of("好奇", "紧张", "平静", "惊讶", "释然", "紧迫", "克制");
    private static final Set<String> RHYTHMS = Set.of("紧凑", "平稳", "舒缓", "递进", "停顿");

    private StoryPlanContract() { }

    /** 返回全部违例；只校验，不补字段、不修改冻结计划。 */
    public static List<String> violations(JsonNode script, JsonNode snapshot) {
        if (script == null || !script.isObject()) return List.of("脚本不是 JSON 对象");
        String version = script.path("schema_version").asString("");
        if (version.isBlank() || "1".equals(version)) {
            return script.has("story_plan") ? List.of("story_plan 仅用于 schema_version=2") : List.of();
        }
        if (!"2".equals(version)) {
            return List.of("schema_version 只支持 1 或 2，收到 " + version);
        }
        JsonNode plan = script.path("story_plan");
        if (!plan.isObject()) {
            return List.of("v2 脚本缺少对象 story_plan");
        }
        return validate(plan, script.path("scenes"), snapshot);
    }

    /** 正式写旁白前的计划闸：此时还没有脚本，只判计划自身与快照。 */
    public static List<String> planViolations(JsonNode plan, JsonNode snapshot) {
        if (plan == null || !plan.isObject()) return List.of("故事计划不是 JSON 对象");
        return validate(plan, null, snapshot);
    }

    /** 计划与 story/1 的位置关系；位置不成立的计划不允许进入旁白阶段。 */
    static List<String> storyPlanViolations(JsonNode plan, EditorialStory story) {
        if (story == null || plan == null || !plan.path("scenes").isArray() || plan.path("scenes").isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        JsonNode scenes = plan.path("scenes");
        Set<String> envelope = new LinkedHashSet<>(story.allEvidenceRefs());
        for (JsonNode scene : scenes) {
            for (JsonNode ref : scene.path("evidenceRefs")) {
                if (!envelope.contains(ref.asString(""))) {
                    items.add("计划证据 " + ref.asString("") + " 不在 story/1 信封内");
                }
            }
        }

        EditorialStory.Tension tension = story.tension();
        Set<String> openingPaths = plannedRefs(scenes.get(0));
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

        List<EditorialStory.Claim> facts = story.factClaims();
        int middle = Math.max(0, scenes.size() - 2);
        if (middle < facts.size()) {
            items.add("中段只有 " + middle + " 段，讲不完 " + facts.size() + " 条事实主张");
        }
        Set<String> middlePaths = new LinkedHashSet<>();
        for (int index = 1; index < scenes.size() - 1; index++) {
            middlePaths.addAll(plannedRefs(scenes.get(index)));
        }
        for (EditorialStory.Claim claim : facts) {
            boolean covered = claim.evidenceRefs().stream().anyMatch(middlePaths::contains);
            if (!covered) {
                items.add("事实主张 " + claim.id() + " 的出处没有出现在中段");
            }
        }
        return List.copyOf(new LinkedHashSet<>(items));
    }

    private static Set<String> plannedRefs(JsonNode scene) {
        Set<String> result = new LinkedHashSet<>();
        if (scene == null || !scene.isObject()) {
            return result;
        }
        for (JsonNode ref : scene.path("evidenceRefs")) {
            if (ref.isString()) result.add(ref.asString());
        }
        return result;
    }

    private static List<String> validate(JsonNode plan, JsonNode actual, JsonNode snapshot) {
        List<String> items = new ArrayList<>();
        for (String field : PLAN_FIELDS) {
            if (!plan.has(field)) items.add("story_plan 缺字段 " + field);
        }
        for (String field : plan.propertyNames()) {
            if (!PLAN_FIELDS.contains(field)) items.add("story_plan 有未知字段 " + field);
        }
        if (!SCHEMA_VERSION.equals(plan.path("schemaVersion").asString(""))) {
            items.add("story_plan.schemaVersion 不是 " + SCHEMA_VERSION);
        }
        String expectedSha = snapshot.path("data_sha256").asString("");
        if (!expectedSha.equals(plan.path("datasetSha256").asString(""))) {
            items.add("story_plan.datasetSha256 与快照不一致");
        }
        if (!DatasetTitleAuthor.KINDS.contains(plan.path("hookType").asString(""))) {
            items.add("story_plan.hookType 未知或为空");
        }
        text(items, plan, "coreThesis", 80);
        scenes(items, plan, actual, snapshot);
        return List.copyOf(new LinkedHashSet<>(items));
    }

    private static void scenes(List<String> items, JsonNode plan, JsonNode actual, JsonNode snapshot) {
        JsonNode planned = plan.path("scenes");
        if (!planned.isArray()) {
            items.add("story_plan.scenes 不是数组");
            return;
        }
        if (planned.size() < 3 || planned.size() > 6) {
            items.add("故事计划屏数 " + planned.size() + " 不在 3–6");
        }
        if (actual != null && (!actual.isArray() || planned.size() != actual.size())) {
            items.add("故事计划屏数与脚本 scenes 不一致");
            return;
        }
        Set<String> emotions = new LinkedHashSet<>();
        Set<String> rhythms = new LinkedHashSet<>();
        for (int index = 0; index < planned.size(); index++) {
            int number = index + 1;
            JsonNode scene = planned.get(index);
            JsonNode scriptScene = actual == null ? null : actual.get(index);
            if (!scene.isObject() || (scriptScene != null && !scriptScene.isObject())) {
                items.add("第 " + number + " 屏计划或脚本不是对象");
                continue;
            }
            if (number == 1 && !StoryboardContract.OPENING_BEAT.equals(scene.path("beat").asString(""))) {
                items.add("首屏计划 beat 应为 HOOK");
            }
            if (number == planned.size()
                && !StoryboardContract.CLOSING_BEATS.contains(scene.path("beat").asString(""))) {
                items.add("末屏计划 beat 应为 CONCLUSION、INSIGHT 或 REVEAL");
            }
            for (String field : SCENE_FIELDS) {
                if (!scene.has(field)) items.add("第 " + number + " 屏计划缺字段 " + field);
            }
            for (String field : scene.propertyNames()) {
                if (!SCENE_FIELDS.contains(field)) items.add("第 " + number + " 屏计划有未知字段 " + field);
            }
            text(items, scene, "purpose", 60);
            String beat = scene.path("beat").asString("");
            if (!StoryboardContract.BEATS.contains(beat)) items.add("第 " + number + " 屏计划 beat 未知或为空");
            String emotion = scene.path("emotion").asString("");
            if (!EMOTIONS.contains(emotion)) {
                items.add("第 " + number + " 屏计划 emotion 未知或为空");
            } else {
                emotions.add(emotion);
            }
            String rhythm = scene.path("rhythm").asString("");
            if (!RHYTHMS.contains(rhythm)) {
                items.add("第 " + number + " 屏计划 rhythm 未知或为空");
            } else {
                rhythms.add(rhythm);
            }
            JsonNode visual = scene.path("expectedVisual");
            if (!visual.isObject()) {
                items.add("第 " + number + " 屏计划缺少 expectedVisual 对象");
            } else {
                for (String field : VISUAL_FIELDS) {
                    if (!visual.has(field)) items.add("第 " + number + " 屏 expectedVisual 缺字段 " + field);
                }
                for (String field : visual.propertyNames()) {
                    if (!VISUAL_FIELDS.contains(field)) {
                        items.add("第 " + number + " 屏 expectedVisual 有未知字段 " + field);
                    }
                }
                String type = visual.path("sceneType").asString("");
                if (!SCENE_TYPES.contains(type)) {
                    items.add("第 " + number + " 屏 expectedVisual.sceneType 未知或为空");
                } else if (scriptScene != null && !type.equals(scriptScene.path("scene_type").asString(""))) {
                    items.add("第 " + number + " 屏 scene_type 未消费 expectedVisual：" + type);
                }
                text(items, visual, "description", 40);
            }
            if (scriptScene != null && !beat.isBlank() && !beat.equals(scriptScene.path("beat").asString(""))) {
                items.add("第 " + number + " 屏 beat 未消费故事计划：" + beat);
            }
            evidence(items, number, scene, scriptScene, snapshot);
        }
        if (planned.size() > 1 && emotions.size() == 1) {
            items.add("故事计划 emotion 不得全篇相同");
        }
        if (planned.size() > 1 && rhythms.size() == 1) {
            items.add("故事计划 rhythm 不得全篇相同");
        }
    }

    private static void evidence(List<String> items, int number, JsonNode planned, JsonNode actual,
                                 JsonNode snapshot) {
        JsonNode refs = planned.path("evidenceRefs");
        if (!refs.isArray() || refs.isEmpty() || refs.size() > 4) {
            items.add("第 " + number + " 屏 evidenceRefs 必须是 1–4 条的数组");
            return;
        }
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode ref : refs) {
            String path = ref.asString("");
            if (!path.startsWith("facts.") || !scalar(snapshot, path)) {
                items.add("第 " + number + " 屏证据 " + path + " 不是快照 facts 标量");
            } else if (!declared.add(path)) {
                items.add("第 " + number + " 屏 evidenceRefs 重复：" + path);
            }
        }
        if (actual == null) {
            return;
        }
        Set<String> used = new LinkedHashSet<>(StoryScriptAlignment.factPaths(actual.path("lead").asString("")));
        used.addAll(StoryScriptAlignment.factPaths(actual.path("body").asString("")));
        for (String path : declared) {
            if (!used.contains(path)) items.add("第 " + number + " 屏旁白未消费计划证据 " + path);
        }
        for (String path : used) {
            if (!declared.contains(path)) items.add("第 " + number + " 屏旁白引用计划外证据 " + path);
        }
    }

    private static boolean scalar(JsonNode snapshot, String path) {
        try {
            JsonNode value = DatasetFacts.at(snapshot, path);
            return value != null && (value.isNumber() || value.isString());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void text(List<String> items, JsonNode node, String field, int maxCodePoints) {
        if (!node.has(field)) return;
        JsonNode valueNode = node.path(field);
        if (!valueNode.isString()) {
            items.add(field + " 必须是 JSON 字符串");
            return;
        }
        String value = valueNode.asString();
        int length = value.codePointCount(0, value.length());
        if (value.isBlank()) items.add(field + " 为空");
        else if (length > maxCodePoints) items.add(field + " " + length + " 字，超过上限 " + maxCodePoints + " 字");
    }
}
