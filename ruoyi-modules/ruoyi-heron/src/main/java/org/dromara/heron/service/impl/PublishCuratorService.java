package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronPublishInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

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

    private final OpenAiModelClient model;
    private final PublishInventoryService inventory;
    @Value("${heron.publish.auto-score-threshold:70}")
    private int autoScoreThreshold;

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
            int total = 0;
            for (JsonNode item : breakdown) {
                total += item.path("score").asInt(0);
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

    private static String prompt(List<HeronPublishInventory> rows) {
        StringBuilder text = new StringBuilder("待评库存条目：\n");
        for (HeronPublishInventory row : rows) {
            text.append("- id=").append(row.getId()).append(" 产线=").append(row.getBizLine())
                .append(" 时长=").append(Math.round(row.getDurationMs() / 1000.0)).append("秒");
            if (row.getCandidateId() != null) {
                text.append(" 选题=").append(row.getCandidateId());
            }
            text.append('\n');
        }
        return text.append("\n按系统提示逐条输出评估数组。").toString();
    }

    private static String resource() throws IOException {
        return new ClassPathResource(SYSTEM).getContentAsString(StandardCharsets.UTF_8);
    }
}
