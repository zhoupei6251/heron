package org.dromara.heron.service.impl.shared.quality;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.lane.databoard.DatasetFacts;
import org.dromara.heron.service.impl.lane.databoard.DatasetVisualFocus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质量报告的结构契约。
 *
 * <p>三条硬规矩：报告只有一份且写完不改；主观与客观分层，混在一起就没法复核；
 * 汇总只分类计数，不合成一个「质量分」——一个分数会把「算出来的」和「模型说的」混成同一种权威。
 */
public final class QualityReportContract {

    static final String SCHEMA_VERSION = "quality-review/1";
    static final String PROMPT_VERSION = "quality-review-v1";

    private static final Set<String> VERDICTS = Set.of("pass", "minor", "fail", "unknown");
    private static final Set<String> CLASSES = Set.of("objective", "subjective", "hypothesis");
    private static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
    private static final Set<String> SEVERITIES = Set.of("none", "minor", "major", "blocker");
    private static final Set<String> MODES = Set.of("final", "draft", "sample");
    private static final Set<String> SCREENSHOT_LEVELS = Set.of("contact_sheet", "frames_extracted",
        "browser_render");
    private static final Set<String> NOTES = Set.of("model_output_invalid", "model_failed",
        "model_objective_dropped", "model_blocker_dropped", "gate_drift", "focus_tautological",
        "repair_rolled_back", "repair_not_rechecked", "audio_bgm_dropped");

    /** 定位符只能指向这些来源；别的写法视为无法复核。 */
    private static final Pattern LOCATOR = Pattern.compile(
        "^(script|dataset|checks|focus|subtitles)[^\\s]*$");

    private static final int MAX_QUOTE = 500;

    private QualityReportContract() {
    }

    /**
     * 校验并补全一份报告。
     *
     * <p>校验失败抛 409，不落半成品：一份结构不对的报告比没有报告更坏——
     * 前端会把它当成「审过了」。
     *
     * @param report 待校验的报告
     * @param ctx    评审上下文，用于逐条解析定位符
     * @return 校验后的报告（summary 已按 criteria 重算）
     */
    public static ObjectNode validate(ObjectNode report, QualityCriteria.Context ctx) {
        if (report == null) throw invalid("质量报告为空");
        rejectUnknownFields(report, Set.of("schemaVersion", "versionId", "mode", "reviewedAt", "promptVersion",
            "modelId", "criteria", "summary", "repair", "screenshot", "notes"));
        if (!SCHEMA_VERSION.equals(report.path("schemaVersion").asString(""))) {
            throw invalid("schemaVersion 必须是 " + SCHEMA_VERSION);
        }
        if (report.path("versionId").asLong(-1) != ctx.versionId()) {
            throw invalid("versionId 与当前版本不一致");
        }
        String mode = report.path("mode").asString("");
        if (!MODES.contains(mode)) throw invalid("未知 mode：" + mode);
        if (report.path("reviewedAt").asString("").isBlank()) throw invalid("缺少 reviewedAt");
        if (report.path("promptVersion").asString("").isBlank()) throw invalid("缺少 promptVersion");
        if (report.path("modelId").asString("").isBlank()) throw invalid("缺少 modelId");

        JsonNode criteria = report.path("criteria");
        if (!criteria.isArray() || criteria.size() != QualityCriteria.IDS.size()) {
            throw invalid("criteria 必须是 " + QualityCriteria.IDS.size() + " 条");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ObjectNode> normalized = new ArrayList<>();
        for (JsonNode item : criteria) {
            if (!item.isObject()) throw invalid("criteria 条目不是对象");
            ObjectNode entry = normalizeCriterion((ObjectNode) item, ctx);
            if (!seen.add(entry.path("id").asString(""))) {
                throw invalid("判据重复：" + entry.path("id").asString(""));
            }
            normalized.add(entry);
        }
        if (!seen.equals(new LinkedHashSet<>(QualityCriteria.IDS))) {
            Set<String> missing = new LinkedHashSet<>(QualityCriteria.IDS);
            missing.removeAll(seen);
            throw invalid("缺少判据：" + String.join("、", missing));
        }
        report.set("criteria", JsonNodeFactory.instance.arrayNode().addAll(normalized));
        JsonNode incomingSummary = report.path("summary");
        if (incomingSummary.isObject()) {
            rejectUnknownFields((ObjectNode) incomingSummary, Set.of("pass", "minor", "fail", "unknown",
                "objectiveFail", "subjectiveFail", "hypothesisFail", "objectiveVerified", "subjectiveVerified"));
        }
        JsonNode notes = report.path("notes");
        if (notes.isArray()) {
            for (JsonNode note : notes) {
                if (!knownNote(note.asString(""))) {
                    throw invalid("未知的备注码：" + note.asString(""));
                }
            }
        }
        report.set("summary", summary(normalized));
        return report;
    }

    /**
     * 规范化一条判据，并把解析不了的定位符降级为 unknown。
     *
     * <p>降级只影响这一条：其余条目照常保留。整份报告因为一条定位符写错就作废，
     * 等于让格式错误吞掉全部信息。
     */
    static ObjectNode normalizeCriterion(ObjectNode item, QualityCriteria.Context ctx) {
        rejectUnknownFields(item, Set.of("id", "verdict", "class", "confidence", "machineVerifiable",
            "severity", "repairable", "evidence", "reason"));
        String id = item.path("id").asString("");
        if (!QualityCriteria.IDS.contains(id)) throw invalid("未知判据：" + id);
        String verdict = item.path("verdict").asString("");
        if (!VERDICTS.contains(verdict)) throw invalid(id + " 的 verdict 非法：" + verdict);
        String cls = item.path("class").asString("");
        if (!CLASSES.contains(cls)) throw invalid(id + " 的 class 非法：" + cls);
        String confidence = item.path("confidence").asString("");
        if (!CONFIDENCES.contains(confidence)) throw invalid(id + " 的 confidence 非法：" + confidence);
        String severity = item.path("severity").asString("");
        if (!SEVERITIES.contains(severity)) throw invalid(id + " 的 severity 非法：" + severity);
        boolean machineVerifiable = item.path("machineVerifiable").asBoolean(false);
        boolean repairable = item.path("repairable").asBoolean(false);
        if ("objective".equals(cls)) {
            if (!"high".equals(confidence) || !machineVerifiable) {
                throw invalid(id + " 是客观结论，必须 confidence=high 且 machineVerifiable=true");
            }
            if ("blocker".equals(severity) && !"fail".equals(verdict)) {
                throw invalid(id + " 的 blocker 只能出现在 fail 上");
            }
        } else {
            if (machineVerifiable) throw invalid(id + " 不是客观结论，machineVerifiable 必须为 false");
            if ("subjective".equals(cls) && !"medium".equals(confidence) && !"low".equals(confidence)) {
                throw invalid(id + " 的主观结论只能是 medium 或 low 置信度");
            }
            if ("hypothesis".equals(cls) && !"low".equals(confidence)) {
                throw invalid(id + " 的推测只能是 low 置信度");
            }
            if ("blocker".equals(severity)) throw invalid(id + " 的主观意见不得升级成 blocker");
        }
        if ("pass".equals(verdict) && !"none".equals(severity)) {
            throw invalid(id + " 通过时严重度只能是 none");
        }
        if (repairable && !("minor".equals(verdict) || "fail".equals(verdict))) {
            throw invalid(id + " 只有 minor 或 fail 才可修复");
        }
        if (repairable && !QualityRepairPlanner.repairable(id, item)) {
            // 越界的「可修」只降级这一条，不作废整份报告：模型或代码多说了一句话，
            // 不该让七条已经判清楚的结论跟着消失。
            repairable = false;
        }

        List<ObjectNode> evidence = new ArrayList<>();
        JsonNode list = item.path("evidence");
        if (list.isArray()) {
            for (JsonNode entry : list) {
                if (!entry.isObject()) throw invalid(id + " 的 evidence 条目不是对象");
                rejectUnknownFields((ObjectNode) entry, Set.of("locator", "quote"));
                String locator = entry.path("locator").asString("");
                String quote = entry.path("quote").asString("");
                if (locator.isBlank() || quote.isBlank()) throw invalid(id + " 的 evidence 缺 locator 或 quote");
                if (quote.length() > MAX_QUOTE) throw invalid(id + " 的 evidence 引用过长");
                if (quote.contains("/") || quote.contains("\\")) {
                    throw invalid(id + " 的 evidence 引用含路径分隔符");
                }
                if (!LOCATOR.matcher(locator).matches()) throw invalid(id + " 的 locator 非法：" + locator);
                ObjectNode normalizedEvidence = JsonNodeFactory.instance.objectNode();
                normalizedEvidence.put("locator", locator);
                normalizedEvidence.put("quote", quote);
                evidence.add(normalizedEvidence);
            }
        }
        if (evidence.isEmpty() && !"unknown".equals(verdict)) {
            throw invalid(id + " 缺少证据");
        }
        String reason = item.path("reason").asString("");
        if (reason.isBlank()) throw invalid(id + " 缺少 reason");
        if (reason.length() > 2000) throw invalid(id + " 的 reason 过长");

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("verdict", verdict);
        out.put("class", cls);
        out.put("confidence", confidence);
        out.put("machineVerifiable", machineVerifiable);
        out.put("severity", severity);
        out.put("repairable", repairable);
        out.set("evidence", JsonNodeFactory.instance.arrayNode().addAll(evidence));
        out.put("reason", reason);
        // 定位符解析不到时整条降级为 unknown：给一条查无实据的结论比不给更坏。
        if (!"unknown".equals(verdict) && !resolvable(item, ctx)) {
            out.put("verdict", "unknown");
            out.put("severity", "none");
            out.put("repairable", false);
            out.put("reason", reason + "（证据定位符无法解析，已降级为 unknown）");
        }
        return out;
    }

    /** 定位符是否能在当前上下文里解析到东西。 */
    private static boolean resolvable(ObjectNode item, QualityCriteria.Context ctx) {
        JsonNode evidence = item.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty()) return false;
        for (JsonNode entry : evidence) {
            if (!resolve(entry.path("locator").asString(""), ctx)) return false;
        }
        return true;
    }

    /**
     * 解析一个定位符。
     *
     * <p>只认五类来源：脚本、快照、闸门 measured、画面焦点、字幕 cue。
     * 解析不到就返回 false——不去「猜」它可能指什么。
     */
    public static boolean resolve(String locator, QualityCriteria.Context ctx) {
        if (locator == null || locator.isBlank()) return false;
        if (locator.startsWith("script.")) {
            return resolveScript(locator.substring("script.".length()), ctx);
        }
        if (locator.startsWith("dataset.")) {
            return ctx.dataset() != null && DatasetFacts.at(ctx.dataset(), locator.substring("dataset.".length())) != null;
        }
        if (locator.startsWith("checks[")) {
            int end = locator.indexOf(']');
            if (end < 0) return false;
            String gate = locator.substring("checks[".length(), end);
            JsonNode gateNode = ctx.gateNode(gate);
            if (gateNode == null) return false;
            String key = locator.substring(end + 1);
            if (key.startsWith(".")) {
                key = key.substring(1);
            }
            if (key.startsWith("measured.")) {
                return gateNode.path("measured").has(key.substring("measured.".length()));
            }
            return gateNode.has(key);
        }
        if (locator.startsWith("focus[")) {
            int end = locator.indexOf(']');
            if (end < 0) return false;
            String inner = locator.substring("focus[".length(), end);
            String rest = locator.substring(end + 1);
            if (!inner.startsWith("scene") || !rest.startsWith(".")) return false;
            int sceneNo;
            try {
                sceneNo = Integer.parseInt(inner.substring("scene".length()));
            } catch (NumberFormatException e) {
                return false;
            }
            String field = rest.substring(1);
            DatasetVisualFocus.Focus focus = ctx.focus().get(sceneNo);
            return focus != null && switch (field) {
                case "mode" -> !focus.mode().isBlank();
                case "refs" -> !focus.refs().isEmpty();
                case "labels" -> true;
                default -> false;
            };
        }
        if (locator.startsWith("subtitles.cue[")) {
            int end = locator.indexOf(']');
            if (end < 0) return false;
            String index = locator.substring("subtitles.cue[".length(), end);
            try {
                int i = Integer.parseInt(index);
                return ctx.subtitles() != null && ctx.subtitles().isArray() && i >= 0
                    && i < ctx.subtitles().size();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean resolveScript(String path, QualityCriteria.Context ctx) {
        String[] parts = path.split("\\.");
        JsonNode node = ctx.script();
        for (String part : parts) {
            if (node == null) return false;
            if (part.startsWith("scenes[")) {
                int end = part.indexOf(']');
                if (end < 0) return false;
                String index = part.substring("scenes[".length(), end);
                int sceneNo;
                try {
                    sceneNo = Integer.parseInt(index) + 1;
                } catch (NumberFormatException e) {
                    return false;
                }
                JsonNode scenes = ctx.scenes();
                if (sceneNo < 1 || sceneNo > scenes.size()) return false;
                node = scenes.get(sceneNo - 1);
                continue;
            }
            if (!node.isObject() || !node.has(part)) return false;
            node = node.get(part);
        }
        return node != null && !node.isNull();
    }

    /**
     * 汇总只分类计数。
     *
     * <p>不合成总分：九条判据的严重程度不可加，加出来的数字既没有刻度也没有出处。
     */
    static ObjectNode summary(List<ObjectNode> criteria) {
        int pass = 0;
        int minor = 0;
        int fail = 0;
        int unknown = 0;
        int objectiveFail = 0;
        int subjectiveFail = 0;
        int hypothesisFail = 0;
        int objectiveVerified = 0;
        int subjectiveVerified = 0;
        for (ObjectNode item : criteria) {
            String verdict = item.path("verdict").asString("");
            String cls = item.path("class").asString("");
            switch (verdict) {
                case "pass" -> pass++;
                case "minor" -> minor++;
                case "fail" -> fail++;
                default -> unknown++;
            }
            if ("fail".equals(verdict)) {
                switch (cls) {
                    case "objective" -> objectiveFail++;
                    case "subjective" -> subjectiveFail++;
                    default -> hypothesisFail++;
                }
            }
            if (!"unknown".equals(verdict)) {
                if ("objective".equals(cls)) {
                    objectiveVerified++;
                } else if ("subjective".equals(cls)) {
                    subjectiveVerified++;
                }
            }
        }
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("pass", pass);
        summary.put("minor", minor);
        summary.put("fail", fail);
        summary.put("unknown", unknown);
        summary.put("objectiveFail", objectiveFail);
        summary.put("subjectiveFail", subjectiveFail);
        summary.put("hypothesisFail", hypothesisFail);
        summary.put("objectiveVerified", objectiveVerified);
        summary.put("subjectiveVerified", subjectiveVerified);
        return summary;
    }

    /** 报告里允许出现的备注码。自由文本不进 notes，避免把意见伪装成证据。 */
    static boolean knownNote(String note) {
        return NOTES.contains(note);
    }

    private static void rejectUnknownFields(ObjectNode node, Set<String> allowed) {
        for (String field : node.propertyNames()) {
            if (!allowed.contains(field)) throw invalid("报告包含未知字段：" + field);
        }
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(409, "quality_report_invalid", reason);
    }

    /** 空报告骨架：模型不可用时也要有一份结构完整的报告。 */
    public static ObjectNode skeleton(long versionId, String mode, String modelId) {
        ObjectNode report = JsonNodeFactory.instance.objectNode();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("versionId", versionId);
        report.put("mode", mode);
        report.put("reviewedAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).toString());
        report.put("promptVersion", PROMPT_VERSION);
        report.put("modelId", modelId);
        report.set("criteria", JsonNodeFactory.instance.arrayNode());
        report.set("summary", JsonNodeFactory.instance.objectNode());
        ObjectNode repair = JsonNodeFactory.instance.objectNode();
        repair.put("attempted", 0);
        repair.put("max", 1);
        repair.set("changedSegments", JsonNodeFactory.instance.arrayNode());
        repair.put("rechecked", false);
        report.set("repair", repair);
        ObjectNode screenshot = JsonNodeFactory.instance.objectNode();
        screenshot.put("level", "contact_sheet");
        screenshot.put("frames", 0);
        screenshot.put("extraBrowserRender", false);
        screenshot.put("reason", "未抽取额外画面证据");
        report.set("screenshot", screenshot);
        report.set("notes", JsonNodeFactory.instance.arrayNode());
        return report;
    }

    /** 组装 criteria 数组。 */
    public static ArrayNode criteriaArray(List<ObjectNode> criteria) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        criteria.forEach(array::add);
        return array;
    }
}
