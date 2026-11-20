package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
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
final class QualityRepairPlanner {

    /** 修复规则：编号 → 允许改的字段。 */
    private static final Map<String, String> WHITELIST = Map.of(
        "hook_3s", "scenes[0].lead",
        "closing_value", "closing",
        "mid_progression", "scenes[].body",
        "no_redundancy", "scenes[].body",
        "pacing_variation", "scenes[].duration_s");

    /** 修复次数上限。与写稿契约的修复轮次不叠加。 */
    static final int MAX_ATTEMPTS = 1;

    private QualityRepairPlanner() {
    }

    /**
     * 这条判据是否允许进入定向修复。
     *
     * <p>白名单之外的判据即使模型说「可修」也不许修：模型能提出的问题，
     * 不等于我们允许它动的地方。
     */
    static boolean repairable(String id, JsonNode item) {
        if (!WHITELIST.containsKey(id)) return false;
        String verdict = item.path("verdict").asString("");
        return "minor".equals(verdict) || "fail".equals(verdict);
    }

    /** 一处定向修改。 */
    record Action(String rule, String criterionId, String field, String instruction) {
    }

    /** 一次修复的全部动作。 */
    record RepairPlan(List<Action> actions) {
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
    static Optional<RepairPlan> plan(JsonNode report, String mode) {
        if (!"draft".equals(mode) && !"sample".equals(mode)) {
            return Optional.empty();
        }
        List<Action> actions = new ArrayList<>();
        for (JsonNode item : report.path("criteria")) {
            String id = item.path("id").asString("");
            if (!WHITELIST.containsKey(id)) continue;
            if (!item.path("repairable").asBoolean(false)) continue;
            String verdict = item.path("verdict").asString("");
            if (!"minor".equals(verdict) && !"fail".equals(verdict)) continue;
            String field = WHITELIST.get(id);
            String instruction = switch (id) {
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
            actions.add(new Action("R-" + id, id, field, instruction));
        }
        if (actions.isEmpty()) return Optional.empty();
        return Optional.of(new RepairPlan(List.copyOf(actions)));
    }

    /**
     * 修复后必须重跑的真实检查。
     *
     * <p>改一句旁白会改掉音轨、cue 边界与画面。只改 JSON 不重渲，
     * 产出的报告会与实际成片脱节。
     */
    static List<String> recheckSteps() {
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
    static String prompt(RepairPlan plan, JsonNode script) {
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
    static ObjectNode emptyRepair() {
        ObjectNode repair = JsonNodeFactory.instance.objectNode();
        repair.put("attempted", 0);
        repair.put("max", MAX_ATTEMPTS);
        repair.set("changedSegments", JsonNodeFactory.instance.arrayNode());
        repair.put("rechecked", false);
        return repair;
    }

    /** 可修判据的集合，供测试与提示词引用。 */
    static Set<String> whitelistIds() {
        return WHITELIST.keySet();
    }
}
