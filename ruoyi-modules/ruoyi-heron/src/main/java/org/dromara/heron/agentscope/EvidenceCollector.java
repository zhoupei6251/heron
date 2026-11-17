package org.dromara.heron.agentscope;

import org.dromara.heron.domain.HeronAgentToolCall;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据链：把模型声明的证据和账本里的真实调用对上。
 *
 * <p>为什么需要核对而不是直接相信：模型可以在 reason 里写任何数字。
 * 「结论要能追溯到证据」这句话只有在能查出「它声称查过的源」和「它真的查过的源」不一致时才有意义。
 *
 * <p>不另存原始返回：账本里已经有每次调用的入出参摘要，这里只存**引用关系**，
 * 免得同一份数据在库里存两遍、两遍还可能对不上。
 */
public final class EvidenceCollector {

    /** 单个摘要片段长度：证据是本体的索引，不是本体。 */
    private static final int MAX_SNIPPET = 400;
    /** 一条研究最多列多少条证据，防止把账本整张抄进来。 */
    static final int MAX_EVIDENCE = 40;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronAgentToolCallMapper mapper;

    public EvidenceCollector(HeronAgentToolCallMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param researchId  研究 id
     * @param attemptNo   第几次执行
     * @param cited       模型自己声明的证据来源
     * @param submitted   模型是否真的交过结论；没交时证据链不完整，要如实标出来
     * @return 证据链 JSON；查不到账本时返回 null，不编一个空壳
     */
    public String collect(long researchId, int attemptNo, List<String> cited, boolean submitted) {
        return collect(researchId, attemptNo, cited, submitted, Map.of());
    }

    /**
     * @param snapshots 本次 attempt 冻结的数据快照，按源索引；空表示没有接线
     */
    public String collect(long researchId, int attemptNo, List<String> cited, boolean submitted,
                          Map<String, ResearchSnapshots.Ref> snapshots) {
        List<HeronAgentToolCall> calls;
        try {
            calls = mapper.lambda()
                .eq(HeronAgentToolCall::getResearchId, researchId)
                .eq(HeronAgentToolCall::getAttemptNo, attemptNo)
                .orderByAsc(HeronAgentToolCall::getCallId)
                .last("LIMIT " + MAX_EVIDENCE)
                .list();
        } catch (RuntimeException e) {
            // 账本读不出来时宁可没有证据链，也不要给一个看起来完整的空壳
            return null;
        }

        ArrayNode evidence = JSON.createArrayNode();
        List<String> citedKeys = cited == null ? List.of() : cited;
        List<String> unmatched = new ArrayList<>(citedKeys);

        for (HeronAgentToolCall call : calls) {
            ObjectNode item = JSON.createObjectNode();
            item.put("callId", String.valueOf(call.getCallId()));
            item.put("round", call.getRoundNo());
            item.put("tool", call.getToolName());
            item.put("status", call.getStatus());
            if (call.getErrorCode() != null) {
                item.put("errorCode", call.getErrorCode());
            }
            item.put("request", snippet(call.getRequestSummary()));
            item.put("response", snippet(call.getResponseSummary()));

            String haystack = haystack(call);
            // 模型说引用过这个来源，就在账本里认领；认领不到的最后单独列出来
            String matched = matchCiting(haystack, unmatched);
            if (matched != null) {
                item.put("cited", matched);
                unmatched.remove(matched);
            }
            // 这次调用碰过哪个已冻结的源：把结论指回具体的数据版本，而不只是「某个 key 出现过」
            String source = matchSource(haystack, snapshots);
            if (source != null) {
                ResearchSnapshots.Ref ref = snapshots.get(source);
                item.put("snapshotSourceKey", source);
                item.put("snapshotSha256", ref.dataSha256());
                item.put("snapshotRetrievedAt", ref.retrievedAt());
            }
            evidence.add(item);
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.put("researchId", String.valueOf(researchId));
        root.put("attemptNo", attemptNo);
        root.put("toolCallCount", calls.size());
        root.put("submitted", submitted);
        root.set("calls", evidence);

        // 模型声称查过、但账本里找不到的：这是最值得看的一项
        ArrayNode ghosts = root.putArray("unverifiedCitations");
        unmatched.forEach(ghosts::add);
        root.set("snapshots", snapshotArray(snapshots));
        root.put("snapshotCount", snapshots == null ? 0 : snapshots.size());
        // 没交结论时证据只能算「过程记录」，不能算「支撑某个判断的证据」
        root.put("complete", submitted && unmatched.isEmpty());
        return root.toString();
    }

    private static String haystack(HeronAgentToolCall call) {
        return (call.getRequestSummary() == null ? "" : call.getRequestSummary())
            + (call.getResponseSummary() == null ? "" : call.getResponseSummary());
    }

    /** 账本里哪次调用碰过这个来源；入参或出参摘要里出现即算。 */
    private static String matchCiting(String haystack, List<String> candidates) {
        for (String candidate : candidates) {
            if (!candidate.isEmpty() && haystack.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 这次调用碰过哪个已冻结的源。 */
    private static String matchSource(String haystack, Map<String, ResearchSnapshots.Ref> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        for (String sourceKey : snapshots.keySet()) {
            if (!sourceKey.isEmpty() && haystack.contains(sourceKey)) {
                return sourceKey;
            }
        }
        return null;
    }

    private static ArrayNode snapshotArray(Map<String, ResearchSnapshots.Ref> snapshots) {
        ArrayNode array = JSON.createArrayNode();
        if (snapshots == null) {
            return array;
        }
        snapshots.forEach((key, ref) -> {
            ObjectNode item = array.addObject();
            item.put("sourceKey", key);
            item.put("dataSha256", ref.dataSha256());
            item.put("retrievedAt", ref.retrievedAt());
        });
        return array;
    }

    private static String snippet(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_SNIPPET ? text : text.substring(0, MAX_SNIPPET) + "…";
    }

    /** 便于日志与报告读的汇总，不入库。 */
    static Map<String, Object> summarize(String evidenceJson) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (evidenceJson == null) {
            summary.put("available", false);
            return summary;
        }
        var root = JSON.readTree(evidenceJson);
        summary.put("available", true);
        summary.put("toolCallCount", root.path("toolCallCount").asInt(0));
        summary.put("complete", root.path("complete").asBoolean(false));
        summary.put("unverifiedCitations", root.path("unverifiedCitations").size());
        return summary;
    }
}
