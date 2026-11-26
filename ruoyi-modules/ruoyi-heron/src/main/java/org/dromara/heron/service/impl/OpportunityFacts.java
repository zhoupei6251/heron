package org.dromara.heron.service.impl;

import org.dromara.heron.agentscope.ResearchGrade;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronNewsCandidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 候选与数据机会的纯事实投影；JSON 缺失或损坏时按缺失事实处理。 */
public record OpportunityFacts(
    String refKey,
    String sourceType,
    String label,
    String title,
    String normalizedTitle,
    String dedupKey,
    LocalDateTime observedAt,
    int summaryLength,
    int bodyLength,
    int evidenceCount,
    int sourceCount,
    ResearchGrade researchGrade,
    Long heatScore,
    Integer bestRank,
    Double coverage,
    Integer points,
    Integer windowPoints,
    boolean hasDataSha256,
    int mergedSourceCount,
    Double zscore,
    Boolean extreme,
    Integer rankDelta,
    String shape,
    String cadence,
    List<String> supportedShapes
) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public OpportunityFacts {
        supportedShapes = supportedShapes == null ? List.of() : List.copyOf(supportedShapes);
    }

    public static OpportunityFacts fromCandidate(HeronNewsCandidate candidate, String sourceType) {
        String title = candidate.getTitle();
        String normalized = title == null ? candidate.getNormalizedTitle() : NewsDedup.normalizedTitle(title);
        LocalDateTime observedAt = candidate.getLastSeenAt() != null ? candidate.getLastSeenAt()
            : candidate.getFirstSeenAt() != null ? candidate.getFirstSeenAt() : candidate.getCreateTime();
        JsonNode evidence = readTree(candidate.getEvidence());
        JsonNode items = evidence == null ? null : evidence.path("items");
        int evidenceCount = items != null && items.isArray() ? items.size() : 0;
        int bodyLength = 0;
        Double confidence = null;
        Set<String> sources = new HashSet<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                // 采集与手工录入没有独立 body 字段：正文落在 items[].summary，
                // 只认 body/content 会让文章的证据维恒为 0。
                bodyLength = Math.max(bodyLength, Math.max(
                    codePointLength(item.path("body").asString("")),
                    Math.max(codePointLength(item.path("content").asString("")),
                        codePointLength(item.path("summary").asString("")))));
                String source = firstText(item, "sourceUrl", "sourceKey", "sourceId");
                if (!source.isEmpty()) {
                    sources.add(source);
                }
                Double itemConfidence = confidence(item);
                if (itemConfidence != null && (confidence == null || itemConfidence > confidence)) {
                    confidence = itemConfidence;
                }
            }
        }
        ResearchGrade researchGrade = "research".equals(sourceType)
            ? ResearchGrade.of(confidence == null ? null : confidence * 100) : null;
        return new OpportunityFacts(
            "candidate:" + candidate.getId(), sourceType, title, title, normalized,
            candidate.getDedupKey(), observedAt, codePointLength(candidate.getSummary()), bodyLength,
            evidenceCount, sources.size(), researchGrade,
            candidate.getHeatScore(), candidate.getBestRank(), null, null, null,
            false, 0, null, null, null, null, null, List.of());
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    public static OpportunityFacts fromOpportunity(HeronDataOpportunity opportunity, String supportedShapes) {
        String headline = opportunity.getHeadline();
        JsonNode signals = readTree(opportunity.getSignals());
        JsonNode snapshot = readTree(opportunity.getSnapshotJson());
        JsonNode signalNode = signals == null ? null : signals;
        JsonNode snapshotNode = snapshot == null ? null : snapshot;
        Integer points = opportunity.getPoints() != null ? opportunity.getPoints()
            : integer(signalNode, "points");
        Integer windowPoints = opportunity.getWindowPoints() != null ? opportunity.getWindowPoints()
            : integer(signalNode, "window_points");
        String shape = text(opportunity.getShape(), firstText(signalNode, "shape"));
        String cadence = text(opportunity.getCadence(), firstText(snapshotNode, "cadence"));
        int mergedSources = mergedSourceCount(signalNode);
        return new OpportunityFacts(
            "opportunity:" + opportunity.getId(), "opportunity", headline, headline,
            headline == null ? "" : NewsDedup.normalizedTitle(headline), opportunity.getDedupKey(),
            opportunity.getObservedAt(), 0, 0, 0, 0, null, null, null,
            number(signalNode, "coverage"), points, windowPoints,
            !firstText(snapshotNode, "data_sha256").isEmpty(), mergedSources,
            number(signalNode, "zscore"),
            signalNode != null && signalNode.hasNonNull("extreme") ? signalNode.path("extreme").asBoolean() : null,
            integer(signalNode, "rank_delta"), shape, cadence, shapes(supportedShapes));
    }

    private static String text(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static int mergedSourceCount(JsonNode signals) {
        Integer count = integer(signals, "merged_count");
        if (count != null && count > 0) {
            return count;
        }
        JsonNode sources = signals == null ? null : signals.path("merged_sources");
        return sources != null && sources.isArray() ? sources.size() + 1 : 1;
    }

    private static List<String> shapes(String supportedShapes) {
        if (supportedShapes == null || supportedShapes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(supportedShapes.split(","))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }

    private static Double number(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        try {
            double value = Double.parseDouble(node.path(field).asString("").strip());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field) {
        Double value = number(node, field);
        return value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || value != Math.rint(value)
            ? null : (int) value.doubleValue();
    }

    private static Double confidence(JsonNode item) {
        try {
            double value = Double.parseDouble(item.path("confidence").asString("").strip());
            return Double.isFinite(value) && value >= 0 && value <= 1 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (RuntimeException e) {
            // 坏 JSON 是缺失事实，不让投影失败；下游按零值/中性分处理。
            return null;
        }
    }

    private static int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    public record Publication(Long versionId, String title, String dedupKey, LocalDateTime publishedAt) {
    }
}
