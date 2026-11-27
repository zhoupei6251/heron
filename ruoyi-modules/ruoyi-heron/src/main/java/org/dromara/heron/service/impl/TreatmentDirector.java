package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整片创意规划：叙事观点 → 视觉世界 → 分镜。
 *
 * <p>三段都是单次结构化 LLM 调用（纯推理无工具需求，不套 ReAct），Java 只做契约校验与硬边界：
 * 镜头数上限、每镜旁白窗、词汇闭集、Profile 锚定。任何一段不合法即抛——
 * 规划失败不该靠静默回退到固定画面蒙混，那是把导演权又收回了 Java。
 *
 * <p>失败回退：调用方拿不到 Treatment 时沿用确定性 storyboard（Agent 仍是自由度，不是可用性依赖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatmentDirector {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper VISUAL_JSON = JsonMapper.builder().build();
    private static final int MAX_SHOTS = 8;
    private static final int MIN_SHOTS = 3;
    private static final int MAX_TEXT = 80;
    private static final List<String> TREATMENTS = List.of(
        "hook", "chart", "image", "statement", "detail", "reveal", "contrast", "close");
    private static final List<String> SYNC_MODES = List.of("line", "beat", "none");

    private final OpenAiModelClient model;

    public boolean enabled() {
        return model.modelName() != null && !model.modelName().isBlank();
    }

    /** 一次规划的全部产物：narrative / visual / storyboard 逐段落盘 data/render/&lt;jobId&gt;/。 */
    public record Treatment(String narrative, String visual, List<Map<String, Object>> shots) {

        public boolean vibe() {
            return flag("vibe");
        }

        public boolean noNarration() {
            return flag("noNarration");
        }

        private boolean flag(String key) {
            try {
                JsonNode visual = VISUAL_JSON.readTree(this.visual);
                return visual.path(key).asBoolean(false);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /**
     * 整片三段一次调用产出：故事观点、视觉世界、逐镜设计。
     *
     * <p>一次而不是三次：叙事和视觉本来就要互相咬合，拆三次调用会让视觉
     * 拿不到叙事刚定下的观点。模型一次给全，Java 分段校验。
     */
    public Treatment plan(String scriptJson, String datasetJson, ProfileRegistry.Profile profile,
                          ProductionBizType line, String storyAngle) {
        String reply = model.chatWithoutUsage(prompt(scriptJson, datasetJson, profile, line, storyAngle), null);
        return parse(reply, profile);
    }

    private static String prompt(String scriptJson, String datasetJson, ProfileRegistry.Profile profile,
                                 ProductionBizType line, String storyAngle) {
        StringBuilder text = new StringBuilder("把下面的成片脚本规划成一次视觉创作。");
        if (storyAngle != null && !storyAngle.isBlank()) {
            text.append("\n编辑策划角度：").append(storyAngle);
        }
        text.append("\n内容线：").append(line.name());
        text.append("\n视觉 Profile（颜色字号一律用它的 CSS 变量）：id=").append(profile.id())
            .append(" 画布=").append(profile.width()).append("x").append(profile.height());
        text.append("\n脚本（旁白与标题的唯一真源）：\n").append(scriptJson);
        if (datasetJson != null) {
            text.append("\n冻结数据（画面上任何数字的唯一真源）：\n").append(datasetJson);
        }
        text.append("""

            你是整片导演。先想清楚这条片子要让观众经历什么，再决定镜头。三个产物：

            narrative：核心观点（一句）、观众体验弧（从开场到结尾观众经历什么，≤120字）、钩子设计（前 3 秒如何让人停留）。
            visual：视觉世界（一个贯穿全片的视觉母题，≤60字）、镜头语言要点（≤3条）、运动纪律（≤80字）、vibe（可选，true 时按 Vibe 知识视频创作：视觉隐喻优先、设计观众参与瞬间、母题贯穿全片并在结尾变化含义）、noNarration（可选，true 时无旁白模式：字幕承担全部叙事，音乐节拍驱动画面切换）。
            shots：镜头清单，3-8 个。每个镜头：
            - shotId：s1、s2…
            - storyPurpose：这个镜头在体验弧里干什么的（≤40字）
            - visualIntent：画面具体长什么样、主体是什么（≤80字）
            - treatment：只能从 hook/chart/image/statement/detail/reveal/contrast/close 里选；Vibe 模式优先 reveal/contrast/hook
            - narrationText：该镜旁白（从脚本改写分镜，合计必须覆盖脚本全部内容，不增删事实）；noNarration 模式下写画面字幕文案（完整成句，观众要能读）
            - techniques：≤3 个技法词，从 count-up/split-reveal/stroke-trace/fade-up/slam/line-grow/bar-race/split-screen/ken-burns/timeline-flow 里选
            - syncMode：line/beat/none；Vibe 模式且 BGM 有节拍时优先 beat

            只输出一个 JSON 对象：{"narrative":{"thesis":"","arc":"","hook":""},"visual":{"motif":"","lensNotes":[],"motionDiscipline":"","vibe":false,"noNarration":false},"shots":[…]}
            """);
        return text.toString();
    }

    private Treatment parse(String reply, ProfileRegistry.Profile profile) {
        return parse(reply);
    }

    Treatment parseForTest(String reply) {
        return parse(reply);
    }

    private Treatment parse(String reply) {
        JsonNode node;
        try {
            node = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw invalid("导演规划输出不是 JSON");
        }
        JsonNode narrative = node.path("narrative");
        JsonNode visual = node.path("visual");
        JsonNode shots = node.path("shots");
        if (!narrative.isObject() || !visual.isObject() || !shots.isArray()
            || shots.size() < MIN_SHOTS || shots.size() > MAX_SHOTS) {
            throw invalid("导演规划缺 narrative/visual/shots，或镜头数不在 " + MIN_SHOTS + "-" + MAX_SHOTS);
        }
        requireText(narrative.path("thesis"), "核心观点", 60);
        requireText(narrative.path("arc"), "体验弧", 160);
        requireText(narrative.path("hook"), "钩子设计", 100);
        requireText(visual.path("motif"), "视觉母题", 80);
        if (!visual.path("lensNotes").isArray() || visual.path("lensNotes").size() > 3) {
            throw invalid("镜头语言要点缺失或超过 3 条");
        }
        requireText(visual.path("motionDiscipline"), "运动纪律", 100);
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (JsonNode shot : shots) {
            String id = shot.path("shotId").asString("");
            String treatment = shot.path("treatment").asString("");
            String syncMode = shot.path("syncMode").asString("line");
            if (!id.matches("s[1-9][0-9]{0,2}") || !TREATMENTS.contains(treatment)
                || !SYNC_MODES.contains(syncMode)) {
                throw invalid("镜头 " + id + " 的 id/treatment/syncMode 非法：" + treatment + "/" + syncMode);
            }
            requireText(shot.path("storyPurpose"), id + " 的画面目的", 60);
            requireText(shot.path("visualIntent"), id + " 的视觉意图", 120);
            requireText(shot.path("narrationText"), id + " 的旁白", 400);
            List<String> techniques = new ArrayList<>();
            for (JsonNode technique : shot.path("techniques")) {
                techniques.add(technique.asString(""));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shotId", id);
            item.put("storyPurpose", shot.path("storyPurpose").asString());
            item.put("visualIntent", shot.path("visualIntent").asString());
            item.put("treatment", treatment);
            item.put("narrationText", shot.path("narrationText").asString());
            item.put("techniques", List.copyOf(techniques));
            item.put("syncMode", syncMode);
            parsed.add(item);
        }
        return new Treatment(narrative.toString(), visual.toString(), parsed);
    }

    private static boolean requireText(JsonNode node, String what, int max) {
        String text = node.asString("");
        if (text.isBlank() || text.length() > max) {
            throw invalid(what + " 缺失或超过 " + max + " 字");
        }
        return true;
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(409, "treatment_invalid", "导演规划不合法：" + reason);
    }
}
