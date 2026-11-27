package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L3 审稿：机器判客观，模型判主观，合起来是一份报告。
 *
 * <p>模型只判机器判不了的那些（重点是否讲清、节奏起伏有没有意义）。
 * 模型输出里的客观条目一律丢弃——客观结论只能由代码给出，
 * 否则「算出来的」和「模型说的」就再也分不开了。
 *
 * <p>模型失败时仍交付草稿；已启用的视觉审稿记为未定，不替代客观检查。
 */
@Service
@RequiredArgsConstructor
public class QualityReviewer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/review/quality-review.md");
    private static final String STAGE = "quality-review";

    private final OpenAiModelClient model;

    /**
     * 审一条成片。
     *
     * @param ctx 评审上下文
     * @return 报告 JSON；模型不可用时仍返回只有客观部分的报告
     */
    public String review(ProductionRunVo run, QualityCriteria.Context ctx) {
        return review(run, ctx, null, 0, "");
    }

    /**
     * 单镜帧视觉审稿：镜头渲染反馈闭环用，评一帧画面的构图、可读性与母题一致性。
     * 视觉模型未配置时返回 null（返工是质量增益不是可用性依赖）。
     */
    public String reviewShotFrame(ProductionRunVo run, String shotId, String shotPurpose, String visualWorld,
                                  Path frame) {
        String visionModel = model.visionReviewModel();
        if (visionModel == null || frame == null || !Files.isRegularFile(frame)) {
            return null;
        }
        try {
            byte[] png = Files.readAllBytes(frame);
            if (png.length == 0 || png.length > 6 * 1024 * 1024) {
                return null;
            }
            String prompt = "评估这一个镜头（" + shotId + "，画面目的：" + shotPurpose + "）的中间帧画面质量。"
                + (visualWorld == null || visualWorld.isBlank() ? "" : visualWorld)
                + " 只输出一个 JSON 对象：{\"verdict\":\"pass\"或\"needs_review\",\"findings\":[{\"issue\":\"具体问题\",\"fix\":\"怎么修\"}]}"
                + "（findings 最多 3 条，每条 ≤60 字；pass 时 findings 为空数组）。";
            return model.chatWithContactSheet(run, "shot-visual-rework", visionModel,
                "你是单镜画面审稿员。只看构图、可读性与母题一致性；文字内容是否准确不在你的职责内。", prompt, png);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    String review(ProductionRunVo run, QualityCriteria.Context ctx, Path sheet, int tiles, String profileId) {
        List<QualityCriteria.Criterion> machine = QualityCriteria.judge(ctx);
        String visionModel = sheet == null ? null : model.visionReviewModel();
        String modelName = visionModel == null ? model.modelName() : visionModel;
        ObjectNode report = QualityReportContract.skeleton(ctx.versionId(), ctx.mode(),
            modelName == null || modelName.isBlank() ? "unconfigured" : modelName);
        ObjectNode screenshot = (ObjectNode) report.path("screenshot");
        if (sheet != null) {
            screenshot.put("reason", visionModel == null ? "未配置视觉审稿模型，本报告不评价成片画面"
                : "视觉审稿未返回有效画面结论，请查看联络表");
        }
        if (visionModel != null) {
            report.put("promptVersion", "quality-review-vision-v2");
            screenshot.put("verdict", "undetermined");
        }
        List<ObjectNode> entries = new ArrayList<>();
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (QualityCriteria.Criterion criterion : machine) {
            ObjectNode entry = objectiveEntry(criterion);
            entries.add(entry);
            byId.put(criterion.id(), entry);
        }

        Set<String> pending = new LinkedHashSet<>(QualityCriteria.MODEL_DECIDED);
        pending.removeAll(byId.keySet());
        List<String> notes = new ArrayList<>();
        if (!pending.isEmpty()) {
            try {
                String userPrompt = prompt(ctx, pending, machine);
                String reply = visionModel == null ? model.chat(run, STAGE, SYSTEM, userPrompt)
                    : reviewSheet(run, visionModel, userPrompt + visualEvidence(ctx), sheet, tiles, profileId);
                JsonNode answer = parseReply(reply);
                Map<String, ObjectNode> judged = modelCriteria(answer, pending);
                if (visionModel != null) {
                    String visual = visualFeedback(answer.path("visualCraft"), tiles);
                    if (visual != null) {
                        JsonNode craft = answer.path("visualCraft");
                        screenshot.put("frames", tiles);
                        screenshot.put("reason", visual);
                        screenshot.put("verdict", craft.path("verdict").asString());
                        ArrayNode findings = screenshot.putArray("findings");
                        for (JsonNode finding : craft.path("findings")) {
                            findings.addObject().put("tile", finding.path("tile").asInt())
                                .put("kind", switch (finding.path("kind").asString("")) {
                                    case "readability", "hierarchy", "spacing", "fact" -> finding.path("kind").asString();
                                    default -> "other";
                                })
                                .put("issue", boundedVisualText(finding.path("issue"), 120));
                        }
                    }
                }
                for (String id : pending) {
                    ObjectNode entry = judged.get(id);
                    if (entry == null) {
                        entries.add(unknownEntry(id, "模型未给出这一条的结论"));
                        notes.add("model_output_invalid");
                        continue;
                    }
                    try {
                        entry = QualityReportContract.normalizeCriterion(entry, ctx);
                    } catch (org.dromara.heron.domain.HeronBusinessException e) {
                        entry = unknownEntry(id, "模型结论不符合质量报告契约");
                        notes.add("model_output_invalid");
                    }
                    entries.add(entry);
                    byId.put(id, entry);
                }
            } catch (ModelOutputInvalid e) {
                // 模型输出不合契约：主观部分整份作废，客观部分照常落库，不重试。
                for (String id : pending) {
                    entries.add(unknownEntry(id, "模型未给出这一条的结论"));
                }
                notes.add("model_output_invalid");
            } catch (IOException | RuntimeException e) {
                // 模型不可用：同样只丢主观部分，成片照常交付。
                for (String id : pending) {
                    entries.add(unknownEntry(id, "模型未给出这一条的结论"));
                }
                notes.add("model_failed");
            }
        }

        // 按判据顺序排列，报告读起来才有固定次序。
        entries.sort((left, right) -> {
            int rank = QualityCriteria.IDS.indexOf(left.path("id").asString(""))
                - QualityCriteria.IDS.indexOf(right.path("id").asString(""));
            return rank;
        });
        // 成片的生产事实也进报告：配乐因响度超标被去掉，人得看得见
        if (bgmDropped(ctx)) {
            notes.add("audio_bgm_dropped");
        }
        report.set("criteria", QualityReportContract.criteriaArray(entries));
        report.set("summary", QualityReportContract.summary(entries));
        report.set("repair", QualityRepairPlanner.emptyRepair());
        ArrayNode noteArray = JsonNodeFactory.instance.arrayNode();
        notes.stream().distinct().forEach(noteArray::add);
        report.set("notes", noteArray);
        return QualityReportContract.validate(report, ctx).toString();
    }

    /**
     * 成片是不是因为响度超标去掉了配乐。
     *
     * <p>从 {@code checks} 里读，不另存一份状态：闸里已经记着实测值，
     * 报告再存一遍就成了两处可能对不上的事实。
     */
    private static boolean bgmDropped(QualityCriteria.Context ctx) {
        JsonNode checks = ctx.checks();
        if (checks == null || !checks.isArray()) {
            return false;
        }
        for (JsonNode check : checks) {
            if ("audio_mix".equals(check.path("gate").asString(""))) {
                return check.path("measured").path("bgmDropped").asBoolean(false);
            }
        }
        return false;
    }

    /** 机器定论转成报告条目。 */
    private static ObjectNode objectiveEntry(QualityCriteria.Criterion criterion) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("id", criterion.id());
        entry.put("verdict", criterion.verdict());
        entry.put("class", "objective");
        entry.put("confidence", "high");
        entry.put("machineVerifiable", true);
        entry.put("severity", criterion.severity());
        entry.put("repairable", criterion.repairable());
        ArrayNode evidence = JsonNodeFactory.instance.arrayNode();
        for (QualityCriteria.Evidence item : criterion.evidence()) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("locator", item.locator());
            node.put("quote", item.quote());
            evidence.add(node);
        }
        entry.set("evidence", evidence);
        entry.put("reason", criterion.reason());
        return entry;
    }

    /** 模型没给出的判据：落 unknown + hypothesis，不猜。 */
    private static ObjectNode unknownEntry(String id, String reason) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("id", id);
        entry.put("verdict", "unknown");
        entry.put("class", "hypothesis");
        entry.put("confidence", "low");
        entry.put("machineVerifiable", false);
        entry.put("severity", "none");
        entry.put("repairable", false);
        entry.set("evidence", JsonNodeFactory.instance.arrayNode());
        entry.put("reason", reason);
        return entry;
    }

    /**
     * 模型输出不合契约。
     *
     * <p>与「模型调用失败」分开记：前者是格式问题，后者是服务不可用。
     * 两者都不重试——重试只会让一条本来能交付的片子卡在审稿上。
     */
    private static final class ModelOutputInvalid extends RuntimeException {
        ModelOutputInvalid(String message) {
            super(message);
        }
    }

    private static String visualEvidence(QualityCriteria.Context ctx) {
        if (ctx.dataset() == null || ctx.focus().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n可核对的冻结数据，单位：")
            .append(ctx.dataset().path("metric").path("unit").asString(""));
        ctx.focus().values().stream().flatMap(focus -> focus.refs().stream()).distinct().limit(16).forEach(ref -> {
            JsonNode value = DatasetFacts.at(ctx.dataset(), "facts." + ref);
            if (value != null && value.isValueNode() && value.toString().length() <= 180) {
                out.append('\n').append("facts.").append(ref).append(" = ").append(value);
            }
        });
        return out.toString();
    }

    private String reviewSheet(ProductionRunVo run, String visionModel, String prompt, Path sheet, int tiles,
                               String profileId) throws IOException {
        final int maxBytes = 6 * 1024 * 1024;
        try (InputStream input = Files.newInputStream(sheet)) {
            byte[] png = input.readNBytes(maxBytes + 1);
            if (png.length == 0 || png.length > maxBytes) {
                throw new IOException("联络表大小超出视觉审稿范围");
            }
            return model.chatWithContactSheet(run, STAGE, visionModel, SYSTEM, prompt
                + "\n附有成片联络表，共 " + tiles + " 格，视觉 Profile：" + profileId, png);
        }
    }

    static String visualFeedback(JsonNode review, int tiles) {
        JsonNode feedback = review.path("findings");
        String verdict = review.path("verdict").asString("");
        if (tiles < 1 || !feedback.isArray()) {
            return null;
        }
        if ("pass".equals(verdict) && feedback.isEmpty()) {
            String observation = boundedVisualText(review.path("observation"), 180);
            return observation == null ? null : "抽样画面未见明显构图或可读性问题：" + observation;
        }
        if (!"needs_review".equals(verdict) || feedback.isEmpty() || feedback.size() > 3) {
            return null;
        }
        List<String> findings = new ArrayList<>();
        for (JsonNode item : feedback) {
            JsonNode tile = item.path("tile");
            String issue = boundedVisualText(item.path("issue"), 120);
            String fix = boundedVisualText(item.path("fix"), 120);
            if (!tile.isIntegralNumber() || !tile.canConvertToInt() || tile.asInt() < 1 || tile.asInt() > tiles
                || issue == null || fix == null) {
                return null;
            }
            findings.add("联络表第 " + tile.asInt() + " 格：" + issue + "；建议 " + fix);
        }
        return "建议人工看画面：" + String.join("。", findings);
    }

    private static String boundedVisualText(JsonNode node, int max) {
        String text = node.asString("").replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+", " ").strip();
        return node.isString() && !text.isBlank() && text.length() <= max ? text : null;
    }

    private static JsonNode parseReply(String reply) {
        JsonNode node;
        try {
            node = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw new ModelOutputInvalid("质量审稿输出不是 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new ModelOutputInvalid("质量审稿输出不是 JSON 对象");
        }
        if (!node.path("criteria").isArray()) {
            throw new ModelOutputInvalid("质量审稿输出缺少 criteria");
        }
        return node;
    }

    private static Map<String, ObjectNode> modelCriteria(JsonNode answer, Set<String> pending) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        JsonNode list = answer.path("criteria");
        for (JsonNode item : list) {
            if (!item.isObject()) continue;
            String id = item.path("id").asString("");
            if (!pending.contains(id) || out.containsKey(id)) continue;
            String cls = item.path("class").asString("");
            if ("objective".equals(cls)) continue;
            String severity = item.path("severity").asString("");
            if ("blocker".equals(severity)) continue;
            ObjectNode entry = JsonNodeFactory.instance.objectNode();
            entry.put("id", id);
            entry.put("verdict", item.path("verdict").asString("unknown"));
            entry.put("class", cls.isBlank() ? "subjective" : cls);
            entry.put("confidence", item.path("confidence").asString("low"));
            entry.put("machineVerifiable", false);
            entry.put("severity", "none".equals(severity) ? "none" : severity);
            entry.put("repairable", item.path("repairable").asBoolean(false));
            ArrayNode evidence = JsonNodeFactory.instance.arrayNode();
            JsonNode evidenceList = item.path("evidence");
            if (evidenceList.isArray()) {
                for (JsonNode evidenceItem : evidenceList) {
                    if (!evidenceItem.isObject()) continue;
                    ObjectNode node2 = JsonNodeFactory.instance.objectNode();
                    node2.put("locator", evidenceItem.path("locator").asString(""));
                    node2.put("quote", evidenceItem.path("quote").asString(""));
                    evidence.add(node2);
                }
            }
            entry.set("evidence", evidence);
            entry.put("reason", item.path("reason").asString(""));
            out.put(id, entry);
        }
        return out;
    }

    /**
     * 给模型的提示。
     *
     * <p>只给它判不了客观、需要读的内容：脚本原文、每段焦点、机器已经算出的客观结论摘要。
     * 整个数据快照不塞进去——算术责任在数据闸，让模型自己算只会得到自我确认。
     */
    static String prompt(QualityCriteria.Context ctx, Set<String> pending,
                          List<QualityCriteria.Criterion> machine) {
        StringBuilder text = new StringBuilder();
        text.append("脚本：\n").append(ctx.script()).append('\n');
        text.append("\n每段画面焦点：\n");
        ctx.focus().forEach((sceneNo, focus) -> text.append("scene").append(sceneNo)
            .append(": mode=").append(focus.mode()).append(" refs=").append(String.join(",", focus.refs()))
            .append('\n'));
        Map<String, List<QualityCriteria.Evidence>> evidence = QualityCriteria.modelEvidence(ctx);
        for (String id : pending) {
            text.append("\n").append(id).append(" 的机器证据：\n");
            for (QualityCriteria.Evidence item : evidence.getOrDefault(id, List.of())) {
                text.append("- ").append(item.locator()).append(" = ").append(item.quote()).append('\n');
            }
            if ("pacing_variation".equals(id)) {
                QualityCriteria.PacingFacts facts = QualityCriteria.pacingFacts(ctx);
                text.append("- 最长段 ").append(facts.longestMs()).append(" 毫秒（第 ").append(facts.longestSceneNo())
                    .append(" 段），最短段 ").append(facts.shortestMs()).append(" 毫秒，比值 ")
                    .append(String.format("%.2f", facts.ratio()))
                    .append("，声明与实测对不上的段数 ").append(facts.declaredMismatchCount()).append('\n');
            }
        }
        text.append("\n已有客观结论（不要重复评判，也不要改判）：\n");
        for (QualityCriteria.Criterion criterion : machine) {
            text.append("- ").append(criterion.id()).append(": ").append(criterion.verdict())
                .append("（").append(criterion.reason()).append("）\n");
        }
        text.append("\n只输出 JSON，criteria 里只写这些判据：").append(String.join("、", pending));
        return text.toString();
    }

    private static String resource(String path) {
        try (InputStream stream = QualityReviewer.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }

    /** 报告时间戳统一用 UTC，与审稿记录同风格。 */
    static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).toString();
    }
}
