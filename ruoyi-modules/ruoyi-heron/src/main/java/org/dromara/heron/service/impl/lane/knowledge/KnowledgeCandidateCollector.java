package org.dromara.heron.service.impl.lane.knowledge;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.service.impl.shared.research.NewsDedup;
import org.dromara.heron.service.impl.shared.support.ModelJson;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识主题候选：读知识包条目，让模型产出「一题串 3–5 条」的选题，写进候选表。
 *
 * <p>不生成全书一次性的主题库：每次只给一个窗口的条目，按日期轮换。书有几百条时，
 * 一轮全部塞进提示词既贵又容易让模型只盯着开头几章；轮换让不同章节都有机会被选中。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeCandidateCollector {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/knowledge-explainer/selection.md");
    /** 一次给模型看的条目数：太多会稀释判断，太少跨章串联的选择空间不够。 */
    private static final int SLICE = 60;
    private static final int MAX_ENTRY_IDS = 5;

    private final OpenAiModelClient model;
    private final HeronProductionProperties properties;
    private final HeronNewsCandidateMapper candidateMapper;

    /**
     * 给源补齐候选：池里已有当天未认领、未发布的候选就不再生。
     * 模型失败只记日志不抛——知识线是备用线，不该把数据看板线的槽位拖垮。
     *
     * @return 新增候选数
     */
    public int ensure(String sourceKey) {
        LocalDateTime now = LocalDateTime.now();
        long fresh = candidateMapper.selectCount(QueryBuilder.lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getSourceKind, HeronNewsCandidate.SOURCE_KNOWLEDGE)
            .eq(HeronNewsCandidate::getSourceKey, sourceKey)
            .isNull(HeronNewsCandidate::getPublishedAt)
            .isNull(HeronNewsCandidate::getActiveTaskId)
            .ge(HeronNewsCandidate::getLastSeenAt, now.minusHours(24)).build());
        if (fresh > 0) {
            return 0;
        }
        KnowledgePack pack;
        try {
            pack = KnowledgePack.load(properties.knowledgeDir(), sourceKey);
        } catch (RuntimeException e) {
            log.warn("知识包不可用，跳过选题 source={}: {}", sourceKey, e.getMessage());
            return 0;
        }
        String reply;
        try {
            reply = model.chatWithoutUsage(SYSTEM, prompt(pack, slice(pack)));
        } catch (RuntimeException e) {
            log.warn("知识选题模型调用失败 source={}: {}", sourceKey, e.getMessage());
            return 0;
        }
        int created = 0;
        for (JsonNode item : candidates(reply)) {
            String title = item.path("title").asString("").strip();
            String hook = item.path("hook").asString("").strip();
            List<String> ids = entryIds(item.path("entryIds"), pack);
            if (title.isBlank() || ids.isEmpty() || ids.size() > MAX_ENTRY_IDS) {
                continue;
            }
            if (insert(pack, title, hook, ids, now)) {
                created++;
            }
        }
        if (created == 0) {
            log.warn("知识选题没有产出可用候选 source={}", sourceKey);
        }
        return created;
    }

    /** 按日期轮换一个窗口：同一天内重复触发看到的是同一批，跨天换后面一章的条目。 */
    static List<KnowledgePack.Entry> slice(KnowledgePack pack) {
        List<KnowledgePack.Entry> all = pack.entries();
        if (all.size() <= SLICE) {
            return all;
        }
        long day = System.currentTimeMillis() / 86_400_000L;
        int offset = (int) ((day * SLICE) % all.size());
        List<KnowledgePack.Entry> out = new ArrayList<>(SLICE);
        for (int i = 0; i < SLICE; i++) {
            out.add(all.get((offset + i) % all.size()));
        }
        return out;
    }

    static String prompt(KnowledgePack pack, List<KnowledgePack.Entry> entries) {
        StringBuilder text = new StringBuilder("知识源：").append(pack.title())
            .append("（").append(pack.originUrl()).append("）\n\n条目清单：\n");
        for (KnowledgePack.Entry entry : entries) {
            text.append('[').append(entry.entryId()).append("] ").append(entry.chapterTitle())
                .append(" · ").append(entry.title()).append('\n');
            String plain = entry.plain();
            text.append("  ").append(plain.length() > 80 ? plain.substring(0, 80) + "…" : plain).append('\n');
        }
        return text.append("\n按契约输出 JSON。").toString();
    }

    /** 只认清单里真实存在的 entryId：模型编的 ID 直接丢，不让它在 citation 闸里才暴露。 */
    private static List<String> entryIds(JsonNode node, KnowledgePack pack) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode id : node) {
            String value = id.asString("");
            if (pack.entry(value) != null) {
                ids.add(value);
            }
        }
        return List.copyOf(ids);
    }

    private static List<JsonNode> candidates(String reply) {
        try {
            JsonNode node = reply == null ? null : JSON.readTree(ModelJson.unwrap(reply));
            JsonNode list = node == null ? null : node.path("candidates");
            if (list == null || !list.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            list.forEach(out::add);
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private boolean insert(KnowledgePack pack, String title, String hook, List<String> ids, LocalDateTime now) {
        String dedup = DigestUtil.sha256Hex("knowledge|" + pack.sourceKey() + "|" + NewsDedup.normalizedTitle(title));
        if (candidateMapper.selectOne(QueryBuilder.lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getDedupKey, dedup).build()) != null) {
            return false;
        }
        HeronNewsCandidate candidate = new HeronNewsCandidate();
        candidate.setId(IdUtil.getSnowflakeNextId());
        candidate.setTitle(title);
        candidate.setSummary(hook);
        candidate.setNormalizedTitle(NewsDedup.normalizedTitle(title));
        candidate.setDedupKey(dedup);
        candidate.setSourceKind(HeronNewsCandidate.SOURCE_KNOWLEDGE);
        candidate.setSourceKey(pack.sourceKey());
        candidate.setHitCount(1L);
        candidate.setHeatScore(1L);
        candidate.setFirstSeenAt(now);
        candidate.setLastSeenAt(now);
        candidate.setEvidence(evidence(pack, ids));
        candidate.setCreateTime(now);
        candidate.setUpdateTime(now);
        if (candidateMapper.insert(candidate) != 1) {
            throw new org.dromara.heron.domain.HeronBusinessException(409, "candidate_write", "候选保存失败");
        }
        return true;
    }

    /** 证据条目就是 entryId 列表：线内编辑复用 evidenceIds() 取引用，不再另开字段。 */
    private static String evidence(KnowledgePack pack, List<String> ids) {
        ArrayNode items = JSON.createArrayNode();
        for (String id : ids) {
            KnowledgePack.Entry entry = pack.entry(id);
            ObjectNode item = items.addObject();
            item.put("sourceItemId", id);
            item.put("sourceUrl", entry.sourceUrls().isEmpty() ? pack.originUrl() : entry.sourceUrls().get(0));
            item.put("title", entry.title());
            item.put("summary", entry.plain());
            item.put("topicOnly", false);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("items", items);
        root.put("evidenceTruncated", false);
        return root.toString();
    }

    private static String resource(String path) {
        try (InputStream stream = KnowledgeCandidateCollector.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
