package org.dromara.heron.domain;

import cn.hutool.crypto.digest.DigestUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 候选池内容摘要：证明「总编辑看到的」和「线内编辑挑的」是同一份数据。
 *
 * <p>只纳入两阶段实际会读的字段，审计字段（create/update/claimed/published）一律不进来——
 * 否则一次无关的认领写库就会让同一份逻辑候选算出不同 hash，校验立刻变成噪音。
 *
 * <p>唯一实现放在这里：{@code NewsProductionDomain.canonical()} 是快照专用的，不要合并。
 */
public final class PoolDigest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PoolDigest() {
    }

    public static String of(List<HeronNewsCandidate> hot, List<HeronNewsCandidate> articles,
                            List<HeronDataSource> datasets, List<HeronNewsSource> sources,
                            List<HeronNewsWork> recent) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("articles", candidates(articles));
        canonical.put("datasets", datasets(datasets));
        canonical.put("hot", candidates(hot));
        canonical.put("recent", recent(recent));
        canonical.put("sources", sources(sources));
        return DigestUtil.sha256Hex(JSON.writeValueAsString(canonical));
    }

    private static List<Map<String, Object>> candidates(List<HeronNewsCandidate> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
            .sorted(Comparator.comparing(HeronNewsCandidate::getCandidateId,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(PoolDigest::candidate)
            .toList();
    }

    private static Map<String, Object> candidate(HeronNewsCandidate item) {
        Map<String, Object> node = new TreeMap<>();
        node.put("bestRank", item.getBestRank());
        node.put("candidateId", item.getCandidateId());
        node.put("evidence", evidence(item.getEvidence()));
        node.put("heatScore", item.getHeatScore());
        node.put("lastSeenAt", epoch(item.getLastSeenAt()));
        node.put("summary", text(item.getSummary()));
        node.put("title", text(item.getTitle()));
        return node;
    }

    /** 证据只算「引用集合」，不算原始 JSON 的键序与缩进。 */
    private static List<String> evidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = new ArrayList<>();
            for (JsonNode item : JSON.readTree(raw).path("items")) {
                String id = item.path("sourceItemId").asString("");
                String url = item.path("sourceUrl").asString("");
                ids.add((id.isBlank() ? url : id) + "|" + url);
            }
            ids.sort(Comparator.naturalOrder());
            return List.copyOf(ids);
        } catch (RuntimeException e) {
            // 解析不出来也要确定性：用原文摘要兜底，而不是把原文整段塞进 hash 输入
            return List.of("sha256:" + DigestUtil.sha256Hex(raw));
        }
    }

    private static List<Map<String, Object>> datasets(List<HeronDataSource> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
            .sorted(Comparator.comparing(HeronDataSource::getSourceKey,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(source -> {
                Map<String, Object> node = new TreeMap<>();
                node.put("cadence", text(source.getCadence()));
                node.put("lastUpdated", epoch(source.getLastUpdated()));
                node.put("name", text(source.getName()));
                node.put("shapes", text(source.getShapes()));
                node.put("sourceKey", text(source.getSourceKey()));
                return node;
            })
            .toList();
    }

    private static List<Map<String, Object>> sources(List<HeronNewsSource> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
            .sorted(Comparator.comparing(HeronNewsSource::getSourceId,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(source -> {
                Map<String, Object> node = new TreeMap<>();
                node.put("host", host(source.getUrl()));
                node.put("name", text(source.getName()));
                node.put("sourceId", source.getSourceId());
                return node;
            })
            .toList();
    }

    private static List<String> recent(List<HeronNewsWork> works) {
        if (works == null) {
            return List.of();
        }
        return works.stream().map(work -> text(work.getTitle())).toList();
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 固定按 UTC 取毫秒：LocalDateTime 本身无时区，仓库里统一按 UTC 解释。 */
    private static Long epoch(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
