package org.dromara.heron.service.impl.shared.quality;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 定向修复的白名单与重跑清单。
 *
 * <p>这一层只决定「允许改哪里」，不执行改写：改稿要走既有的写稿链路，
 * 且改完必须整链重跑，否则报告结论会和成片脱节——比不修复更糟。
 *
 * <p>白名单只有五条，且只动文字：数据、事实引用路径、证据出处、段落数量与总时长、
 * 视觉与合规字段一律不许模型触碰。
 */
public final class QualityRepairPlanner {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一处改动涉及原稿里的哪个字段；抄回修复稿时按它取数，别按模型自己给的结构。 */
    private static final String CLOSING = "closing";
    private static final String HOOK_LEAD = "scenes[0].lead";
    private static final String SCENE_BODY = "scenes[].body";
    private static final String SCENE_DURATION = "scenes[].duration_s";

    /** 修复规则：编号 → 允许改的字段。 */
    private static final Map<String, String> WHITELIST = Map.of(
        "hook_3s", HOOK_LEAD,
        "closing_value", CLOSING,
        "mid_progression", SCENE_BODY,
        "no_redundancy", SCENE_BODY,
        "pacing_variation", SCENE_DURATION);

    /** 修复次数上限。与写稿契约的修复轮次不叠加。 */
    public static final int MAX_ATTEMPTS = 1;

    private QualityRepairPlanner() {
    }

    /**
     * 这条判据是否允许进入定向修复。
     *
     * <p>白名单之外的判据即使模型说「可修」也不许修：模型能提出的问题，
     * 不等于我们允许它动的地方。
     */
    public static boolean repairable(String id, JsonNode item) {
        if (!WHITELIST.containsKey(id)) return false;
        String verdict = item.path("verdict").asString("");
        return "minor".equals(verdict) || "fail".equals(verdict);
    }

    /** 一处定向修改。 */
    public record Action(String rule, String criterionId, String field, String instruction) {
    }

    /** 一次修复的全部动作。 */
    public record RepairPlan(List<Action> actions) {
        boolean empty() {
            return actions.isEmpty();
        }
    }

    /**
     * 从报告里挑出可执行的修复动作。
     *
     * <p>正式成片（mode=final）不修：改稿要走写稿链路并整链重渲，那属于「重生产一个新版本」，
     * 不是在已完成的版本上打补丁。draft 与 sample 才允许在本地改一轮。
     *
     * @param report 已通过结构校验的报告
     * @param mode   评审模式；只有 draft / sample 允许修
     * @return 没有可修条目时为空
     */
    public static Optional<RepairPlan> plan(JsonNode report, String mode) {
        if (!"draft".equals(mode) && !"sample".equals(mode)) {
            return Optional.empty();
        }
        return planOf(collect(report));
    }

    /**
     * 正式成片的重生产计划。
     *
     * <p>final 报告不打补丁（见 {@link #plan}），但可以按同一份白名单改稿后重生产一个新版本：
     * 动作与草稿态逐条相同，区别在执行方式——冻结修复稿、跑整条流水线、再复检一次。
     * 白名单外一律不修，与草稿态同一条规矩。
     */
    public static Optional<RepairPlan> planForReproduction(JsonNode report) {
        if (report == null || !report.isObject()) {
            return Optional.empty();
        }
        return planOf(collect(report));
    }

    private static Optional<RepairPlan> planOf(List<Action> actions) {
        if (actions.isEmpty()) return Optional.empty();
        return Optional.of(new RepairPlan(List.copyOf(actions)));
    }

    /** 报告里所有够格进入修复的判据，转成动作。 */
    private static List<Action> collect(JsonNode report) {
        List<Action> actions = new ArrayList<>();
        if (report == null || !report.isObject()) {
            return actions;
        }
        for (JsonNode item : report.path("criteria")) {
            String id = item.path("id").asString("");
            if (!WHITELIST.containsKey(id)) continue;
            if (!item.path("repairable").asBoolean(false)) continue;
            String verdict = item.path("verdict").asString("");
            if (!"minor".equals(verdict) && !"fail".equals(verdict)) continue;
            actions.add(new Action("R-" + id, id, WHITELIST.get(id), instructionOf(id)));
        }
        return actions;
    }

    private static String instructionOf(String id) {
        return switch (id) {
            case "hook_3s" -> "重写第 1 屏开场口播，让它先给出可验证的具体信息；"
                + "不得新增或删除事实引用，不得改动其余字段";
            case "closing_value" -> "重写结尾，给出一个结论或值得开口的问题；"
                + "不得引入新事实，不得改成关注引导";
            case "mid_progression", "no_redundancy" -> "重写相邻两段中靠后那一段的正文，"
                + "让它带来新的信息；保留该段原有的事实引用集合不变";
            case "pacing_variation" -> "调整该段声明时长，使其与实测时长接近；"
                + "不得改动总时长区间与段落数量";
            default -> "按报告所述问题改写对应字段，其余字段原样保留";
        };
    }

    /** 计划里点到的问题，一行一条，交给写稿链路当修复提示。 */
    static List<String> notes(RepairPlan plan) {
        List<String> notes = new ArrayList<>();
        for (Action action : plan.actions()) {
            notes.add(action.criterionId() + "：" + action.instruction());
        }
        return List.copyOf(notes);
    }

    /** 计划点到的问题编号，复检时只查这几条。 */
    public static List<String> criterionIds(RepairPlan plan) {
        Set<String> ids = new LinkedHashSet<>();
        for (Action action : plan.actions()) {
            ids.add(action.criterionId());
        }
        return List.copyOf(ids);
    }

    /** 计划允许改动的字段，去重后的清单。 */
    public static List<String> allowedFields(RepairPlan plan) {
        Set<String> fields = new LinkedHashSet<>();
        for (Action action : plan.actions()) {
            fields.add(action.field());
        }
        return List.copyOf(fields);
    }

    /**
     * 只把白名单字段从修复稿抄回原稿。
     *
     * <p>模型改稿不保证守规矩：它常常顺手润色别的段落、动一下标题。信任它等于让
     * 「只改点名的地方」变成一句口号。所以不比对、不校验它的省心，而是按原稿深拷贝，
     * 只把允许改的字段从修复稿里搬过来——白名单之外的任何改动都在这里消失。
     */
    public static Narrowed narrow(String originalJson, String repairedJson, RepairPlan plan) {
        List<String> violations = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        JsonNode original = read(originalJson);
        JsonNode repaired = read(repairedJson);
        if (original == null || !original.isObject()) {
            violations.add("原稿不是对象");
            return new Narrowed(originalJson, List.of(), List.copyOf(violations));
        }
        if (repaired == null || !repaired.isObject()) {
            violations.add("修复稿不是对象");
            return new Narrowed(originalJson, List.of(), List.copyOf(violations));
        }
        ObjectNode merged = ((ObjectNode) original).deepCopy();
        Set<String> fields = new LinkedHashSet<>(allowedFields(plan));
        JsonNode originalScenes = original.path("scenes");
        boolean touchesScenes = fields.stream().anyMatch(field -> field.startsWith("scenes"));
        if (touchesScenes) {
            JsonNode repairedScenes = repaired.path("scenes");
            if (!originalScenes.isArray() || !repairedScenes.isArray()) {
                violations.add("修复稿或原稿缺少段落数组");
            } else if (originalScenes.size() != repairedScenes.size()) {
                // 段落数量是受保护字段：数量变了就不是「改写某一段」，是重写了结构。
                violations.add("修复稿的段落数量与原稿不一致");
            }
        }
        if (violations.isEmpty() && originalScenes.isArray()) {
            JsonNode repairedScenes = repaired.path("scenes");
            JsonNode mergedScenes = merged.path("scenes");
            for (int index = 0; index < originalScenes.size(); index++) {
                if (!repairedScenes.path(index).isObject() || !mergedScenes.path(index).isObject()) {
                    violations.add("第 " + (index + 1) + " 段不是对象");
                    break;
                }
                String base = "scenes[" + index + "]";
                ObjectNode target = (ObjectNode) mergedScenes.get(index);
                if (fields.contains(HOOK_LEAD) && index == 0) {
                    copyText(originalScenes.get(0), repairedScenes.path(0), target, "lead", base + ".lead", changed);
                }
                if (fields.contains(SCENE_BODY)) {
                    copyText(originalScenes.get(index), repairedScenes.path(index), target, "body",
                        base + ".body", changed);
                }
                if (fields.contains(SCENE_DURATION)) {
                    copyNumber(originalScenes.get(index), repairedScenes.path(index), target, "duration_s",
                        base + ".duration_s", changed);
                }
            }
        }
        // 先段落再结尾：留痕里的改动顺序与稿子读下来的顺序一致。
        if (violations.isEmpty() && fields.contains(CLOSING)) {
            copyText(original, repaired, merged, "closing", "closing", changed);
        }
        return new Narrowed(merged.toString(), List.copyOf(changed), List.copyOf(violations));
    }

    /** 只抄字符串字段：修复稿给了非文本就算没给，保留原值。 */
    private static void copyText(JsonNode from, JsonNode to, ObjectNode target, String field,
                                 String path, List<String> changed) {
        JsonNode value = to.path(field);
        if (!value.isString() || value.asString("").isBlank()) {
            return;
        }
        if (!from.path(field).asString("").equals(value.asString(""))) {
            changed.add(path);
        }
        target.put(field, value.asString(""));
    }

    /** 只抄数值字段：类型不对就保留原值，不替模型做单位换算。 */
    private static void copyNumber(JsonNode from, JsonNode to, ObjectNode target, String field,
                                   String path, List<String> changed) {
        JsonNode value = to.path(field);
        if (!value.isNumber()) {
            return;
        }
        if (from.path(field).asDouble(Double.NaN) != value.asDouble(Double.NaN)) {
            changed.add(path);
        }
        target.set(field, value);
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 抄回白名单字段后的稿子；changedFields 是真正变了的字段路径。 */
    public record Narrowed(String script, List<String> changedFields, List<String> violations) {
        public boolean changed() {
            return changedFields != null && !changedFields.isEmpty();
        }
    }

    /**
     * 修复后必须重跑的真实检查。
     *
     * <p>改一句旁白会改掉音轨、cue 边界与画面。只改 JSON 不重渲，
     * 产出的报告会与实际成片脱节。
     */
    public static List<String> recheckSteps() {
        return List.of(
            "脚本契约校验",
            "旁白数字与占位符校验",
            "区间语义校验",
            "逐段重算画面焦点",
            "标题合法性校验",
            "全部数据看板闸门",
            "重新配音、重新渲染、重新出字幕",
            "重新出联络表",
            "重算场景对齐与字幕闸门实测值");
    }

    /**
     * 修复提示词。
     *
     * <p>提示里只写「改哪里、保留什么」：一旦出现「放宽」「豁免」「这次不算」，
     * 就是在让模型动事实层。事实层的判据在修复前后必须逐字节一致。
     */
    public static String prompt(RepairPlan plan, JsonNode script) {
        StringBuilder text = new StringBuilder();
        text.append("上一版稿子有这些问题需要定向修改：\n");
        for (Action action : plan.actions()) {
            text.append("- ").append(action.criterionId()).append("：")
                .append(action.instruction()).append('\n');
        }
        text.append("\n只改上面点名的字段，其余字段原样保留；事实引用、证据出处、段落数量、")
            .append("总时长区间、图表类型、视觉与合规字段一律不动。\n")
            .append("不得新增或删除任何事实引用。\n")
            .append("当前稿子：\n").append(script.toString());
        return text.toString();
    }

    /** 空计划，用于「没有要修的」这一事实。 */
    public static ObjectNode emptyRepair() {
        ObjectNode repair = JsonNodeFactory.instance.objectNode();
        repair.put("attempted", 0);
        repair.put("max", MAX_ATTEMPTS);
        repair.set("changedSegments", JsonNodeFactory.instance.arrayNode());
        repair.put("rechecked", false);
        return repair;
    }

    /** 可修判据的集合，供测试与提示词引用。 */
    public static Set<String> whitelistIds() {
        return WHITELIST.keySet();
    }
}
