package org.dromara.heron.service.impl.shared.production;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分镜契约：叙事节点、画面组件与上屏文字。
 *
 * <p>脚本契约判的是形状（字段在不在、类型对不对），分镜判的是「这一屏要表达什么」：
 * 叙事节点成不成立、这一屏用什么画面、上屏那行字放不放得下。
 * 这三个问题都要等稿件和模板都齐了才有答案，所以单独一层。
 *
 * <p><b>分镜模式</b>：每一段都带 beat 的稿件才算分镜稿，这时才启用序列与组件多样性检查。
 * 半拉子的分镜（只有几段写了 beat）直接退回——那说明写稿时没想清楚每一屏要干什么，
 * 比不写分镜更糟。整篇没有 beat 的旧稿一律放行，渲出来还是老样子：
 * 契约升级不能让历史作品重渲不出来。
 */
public final class StoryboardContract {

    /** 叙事节点。取值受控，写错一个词就判断不了这一屏的意图。 */
    static final Set<String> BEATS = Set.of("HOOK", "CONTEXT", "QUESTION", "EVIDENCE", "COMPARISON",
        "REVEAL", "EXPLANATION", "INSIGHT", "CONCLUSION");

    /** 画面组件。取值必须与模板 supportedSceneTypes 交叉；全集与 NewsScriptContract 一致，只含渲染端实现了的类型。 */
    static final Set<String> SCENE_TYPES = Set.of("headline", "text", "image", "chart", "compare", "number");

    /** 开场节点：观众第一眼看到的就是它。 */
    static final String OPENING_BEAT = "HOOK";

    /** 收尾节点：给判断或给结论，不能停在铺垫上。 */
    static final Set<String> CLOSING_BEATS = Set.of("CONCLUSION", "INSIGHT", "REVEAL");

    /** 屏幕文字上限。上屏的字比旁白多一个字就挤。 */
    static final int MAX_SCREEN_TEXT = 18;

    private StoryboardContract() { }

    /**
     * 校验一份稿子的分镜，返回全部违例条目。
     *
     * @param resolved 占位符已解析的同一份稿子；没有就传 null（此时按原文判长度）
     */
    public static List<String> violations(JsonNode script, JsonNode resolved) {
        List<String> items = new ArrayList<>();
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            return items;
        }
        JsonNode shown = resolved == null ? scenes : resolved.path("scenes");
        int withBeat = 0;
        int withType = 0;
        Set<String> used = new LinkedHashSet<>();
        for (int index = 0; index < scenes.size(); index++) {
            JsonNode scene = scenes.get(index);
            if (!scene.isObject()) {
                continue;
            }
            int number = index + 1;
            String type = scene.path("scene_type").asString("").trim();
            if (!type.isBlank()) {
                withType++;
                used.add(type);
                if (!SCENE_TYPES.contains(type)) {
                    items.add("第 " + number + " 段 scene_type 未知：" + type);
                }
            }
            String beat = scene.path("beat").asString("").trim();
            if (!beat.isBlank()) {
                withBeat++;
                if (!BEATS.contains(beat)) {
                    items.add("第 " + number + " 段 beat 未知：" + beat);
                }
            }
            JsonNode screen = scene.path("on_screen_text");
            if (!screen.isMissingNode()) {
                String value = screen.asString("");
                // 长度按上屏后的实际字数算：占位符原文比解析结果长得多，
                // 按原文判会把所有带数字的上屏文字都误判成超长。
                JsonNode shownScene = shown.isArray() && index < shown.size() ? shown.get(index) : null;
                String displayed = shownScene == null ? value : shownScene.path("on_screen_text").asString(value);
                int length = displayed.codePointCount(0, displayed.length());
                if (value.isBlank()) {
                    items.add("第 " + number + " 段 on_screen_text 为空");
                } else if (length > MAX_SCREEN_TEXT) {
                    items.add("第 " + number + " 段 on_screen_text " + length + " 字，超过上限 "
                        + MAX_SCREEN_TEXT + " 字");
                }
            }
        }
        if (withBeat == 0) {
            // 整篇没写分镜：旧稿，放行
            return List.copyOf(items);
        }
        if (withBeat != scenes.size()) {
            items.add("分镜不完整：只有 " + withBeat + "/" + scenes.size() + " 段写了 beat，要么整篇都写，要么都不写");
            return List.copyOf(items);
        }
        // 分镜模式：序列与组件都要成立
        String first = scenes.get(0).path("beat").asString("").trim();
        if (!OPENING_BEAT.equals(first)) {
            items.add("首屏 beat 应为 " + OPENING_BEAT + "，实际 " + first);
        }
        String last = scenes.get(scenes.size() - 1).path("beat").asString("").trim();
        if (!CLOSING_BEATS.contains(last)) {
            items.add("末屏 beat 应为 " + String.join("／", CLOSING_BEATS) + " 之一，实际 " + last);
        }
        if (withType != scenes.size()) {
            items.add("分镜不完整：只有 " + withType + "/" + scenes.size() + " 段写了 scene_type");
        } else if (used.size() < 2) {
            // 整篇一个组件等于没有分镜：那还是照着稿子念
            items.add("整篇只有一种画面组件（" + String.join("、", used) + "），画面没有层次");
        }
        return List.copyOf(items);
    }
}
