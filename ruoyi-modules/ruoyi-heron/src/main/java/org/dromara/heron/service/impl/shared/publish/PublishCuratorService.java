package org.dromara.heron.service.impl.shared.publish;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronPublishInventory;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.impl.shared.support.ModelJson;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 发布总编辑：批量评估待评库存条目。
 *
 * <p>纯结构化 LLM 调用，不套 ReAct——评估没有工具需求，白付 token 与故障面。
 * 模型失败只降级为留 pending，不重试：一条片子不该因为评估服务抖动被淘汰。
 * 评分明细 Java 校验（总分=各维之和），对不上整条作废留 pending。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishCuratorService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = "/prompt/publish-curator/curator.md";
    private static final List<String> ACTIONS = List.of("keep", "hold", "reject");
    private static final int MAX_BATCH = 12;
    /** 评分维度闭集与分值上限：总和恰好 100。模型只能在这个集合里打分，多一个少一个都作废。 */
    private static final Map<String, Integer> DIMENSIONS = Map.of(
        "开场吸引力", 20, "受众价值", 20, "新颖性", 15, "叙事节奏", 15,
        "视听质量", 15, "时效性", 10, "产线适配", 5);

    private final OpenAiModelClient model;
    private final PublishInventoryService inventory;
    private final HeronVideoVersionMapper versions;
    @Value("${heron.publish.auto-score-threshold:70}")
    private int autoScoreThreshold;

    /**
     * 评估一批待评库存（最多 {@value #MAX_BATCH} 条）：一次结构化模型调用给出逐条动作与评分，
     * 每条评分维度必须落在闭集内且不超过该维上限，条目通过校验才写库存。
     *
     * <p>模型调用失败或输出条数与输入不符时整轮跳过，条目留在 pending 等下一轮；
     * 单条动作/评分非法只跳过该条，不写任何结论。并发已被评估过的条目由库存层拒绝，原状态不覆盖。
     */
    public void curatePending() {
        List<HeronPublishInventory> pending = inventory.pendingCuration(MAX_BATCH);
        if (pending.isEmpty()) {
            return;
        }
        JsonNode verdicts;
        try {
            String reply = model.chatWithoutUsage(resource(), prompt(pending));
            verdicts = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException | IOException e) {
            log.warn("库存评估未完成，条目留待评估: {}", e.toString());
            return;
        }
        if (!verdicts.isArray() || verdicts.size() != pending.size()) {
            log.warn("库存评估输出条数 {} 与输入 {} 不符，本轮跳过", verdicts.size(), pending.size());
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            curateOne(pending.get(i), verdicts.get(i));
        }
    }

    private void curateOne(HeronPublishInventory row, JsonNode verdict) {
        try {
            String action = verdict.path("action").asString("");
            if (!ACTIONS.contains(action)) {
                log.warn("库存 {} 的评估动作非法：{}", row.getId(), action);
                return;
            }
            JsonNode breakdown = verdict.path("scores");
            if (!breakdown.isObject() || breakdown.isEmpty()) {
                log.warn("库存 {} 缺评分明细，留待评估", row.getId());
                return;
            }
            // 逐项校验：维度必须是闭集成员且不重复，分值不超该维上限——只卡总分会被多编一维凑分绕过
            int total = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, JsonNode> entry : breakdown.properties()) {
                String dimension = entry.getKey();
                Integer cap = DIMENSIONS.get(dimension);
                int score = entry.getValue().path("score").asInt(-1);
                if (cap == null || !seen.add(dimension) || score < 0 || score > cap) {
                    log.warn("库存 {} 的评分维度非法或越界：{}={}，留待评估", row.getId(), dimension, score);
                    return;
                }
                total += score;
            }
            if (seen.size() != DIMENSIONS.size()) {
                log.warn("库存 {} 评分缺维度（{} / {}），留待评估", row.getId(), seen.size(), DIMENSIONS.size());
                return;
            }
            if (total > 100 || total < 0) {
                log.warn("库存 {} 评分总和 {} 越界，留待评估", row.getId(), total);
                return;
            }
            int expiresHours = verdict.path("expiresHours").asInt(48);
            if (expiresHours < 1 || expiresHours > 24 * 90) {
                log.warn("库存 {} 有效期越界，留待评估", row.getId());
                return;
            }
            inventory.curate(row, switch (action) {
                case "keep" -> HeronPublishInventory.CURATION_ELIGIBLE;
                case "hold" -> HeronPublishInventory.CURATION_HOLD;
                default -> HeronPublishInventory.CURATION_REJECTED;
            }, total, breakdown.toString(),
                verdict.path("reason").asString(""), "publish-curator-v1",
                LocalDateTime.now().plusHours(expiresHours));
        } catch (HeronBusinessException e) {
            // 已被并发评估过：留现状态，不覆盖
        }
    }

    private String prompt(List<HeronPublishInventory> rows) {
        StringBuilder text = new StringBuilder("待评库存条目：\n");
        for (HeronPublishInventory row : rows) {
            text.append("- id=").append(row.getId()).append(" 产线=").append(row.getBizLine())
                .append(" 时长=").append(Math.round(row.getDurationMs() / 1000.0)).append("秒\n");
            // 没有实际内容就只凭时长产线打分——评分必须基于作品本身
            appendContent(text, row);
        }
        return text.append("\n按系统提示逐条输出评估数组。").toString();
    }

    private void appendContent(StringBuilder text, HeronPublishInventory row) {
        HeronVideoVersion version = versions.selectById(row.getVersionId());
        if (version == null) {
            log.warn("库存 {} 的版本不可读，评分基于产线与时长", row.getId());
            return;
        }
        try {
            JsonNode script = JSON.readTree(version.getScript());
            text.append("  标题：").append(script.path("title").asString("")).append('\n');
            for (JsonNode scene : script.path("scenes")) {
                text.append("  段落：").append(scene.path("lead").asString("")).append("。")
                    .append(scene.path("body").asString("")).append('\n');
            }
            text.append("  结尾：").append(script.path("closing").asString("")).append('\n');
        } catch (RuntimeException e) {
            log.warn("库存 {} 的脚本不可读：{}", row.getId(), e.toString());
        }
        if (version.getQualityReport() != null) {
            try {
                JsonNode report = JSON.readTree(version.getQualityReport());
                JsonNode screenshot = report.path("screenshot");
                if (screenshot.has("verdict")) {
                    text.append("  视觉审稿：").append(screenshot.path("verdict").asString(""));
                    if (screenshot.has("reason")) {
                        text.append("（").append(screenshot.path("reason").asString(""), 0, 120).append("）");
                    }
                    text.append('\n');
                }
            } catch (RuntimeException ignored) {
                // 质量报告读不出就当没有
            }
        }
    }

    private static String resource() throws IOException {
        return new ClassPathResource(SYSTEM).getContentAsString(StandardCharsets.UTF_8);
    }
}
