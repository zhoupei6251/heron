package org.dromara.heron.agentscope.research;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结构化研究报告：把一次研究的判断、要点、风险与它依据的快照版本组装成一份可机读的文档。
 *
 * <p>为什么不直接把 {@code result_text} 当报告：那段自然语言是给人看的，
 * 下游（内容决策中心、生产接入）没法从里面稳定取出 decision、证据和口径。
 * 两边都要，但不能只有一边。
 *
 * <p>为什么绑定快照而不是只记时间：plan.md §8 明写「不能只保存查询时间而不保存数据版本」。
 * 源是会变的——实测过同一条源几十分钟内从 65 个点变成 0 个点，
 * 只留一个时间戳的话，这条结论此后再也无人能复核。
 */
public final class ResearchReport {

    /** 报告契约版本；读取方只在认出这个版本时才认为报告可信。 */
    public static final String SCHEMA = "1.0";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ResearchReport() {
    }

    /**
     * 组装报告。
     *
     * @param findings    模型交的结论，可能没交
     * @param evidenceJson 证据链 JSON，可能为 null
     * @param snapshot    研究依据的落库快照，可能为 null
     * @param meta        运行时元信息
     * @return 报告 JSON 文本
     */
    public static String build(ResearchFindings findings, String evidenceJson,
                               JsonNode snapshot, Meta meta) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", SCHEMA);
        root.put("submitted", findings != null && findings.submitted());

        if (findings != null && findings.submitted()) {
            root.put("decision", findings.decisionType());
            root.put("reason", findings.reason());
            putNullable(root, "angle", findings.angle());
            putNullable(root, "confidence",
                findings.confidence() == null ? null : findings.confidence().toPlainString());
            putArray(root, "keyInsights", findings.keyInsights());
            putArray(root, "riskWarnings", findings.riskWarnings());
            putArray(root, "cited", findings.cited());
        } else {
            // 没交结论就明确写没有，不要留一个看起来「结论为空」的结构让下游去猜
            root.put("decision", (String) null);
        }

        root.set("snapshot", snapshotNode(snapshot));
        root.put("evidenceAvailable", evidenceJson != null);
        if (evidenceJson != null) {
            root.set("evidence", JSON.readTree(evidenceJson));
        }

        ObjectNode metaNode = root.putObject("meta");
        metaNode.put("model", meta.model());
        metaNode.put("promptVersion", meta.promptVersion());
        metaNode.put("agentVersion", meta.agentVersion());
        metaNode.put("attemptNo", meta.attemptNo());
        return root.toString();
    }

    /**
     * 快照绑定：只留标识与规模，不留数据本身。
     *
     * <p>{@code data_sha256} 是这一份的唯一标识；{@code retrieved_at} 是它什么时候取的。
     * 两个都要——只留时间等于说「那天取过」，留了 hash 才能证明「取的就是这一份」。
     *
     * @param snapshot 研究依据的快照，可为 null 或非对象
     * @return 绑定节点；没有可用 hash 时 bound=false
     */
    static ObjectNode snapshotNode(JsonNode snapshot) {
        ObjectNode node = JSON.createObjectNode();
        if (snapshot == null || !snapshot.isObject()) {
            node.put("bound", false);
            return node;
        }
        String sha = snapshot.path("data_sha256").asString("");
        node.put("bound", !sha.isEmpty());
        node.put("contract", snapshot.path("contract").asString(""));
        node.put("shape", snapshot.path("shape").asString(""));
        node.put("cadence", snapshot.path("cadence").asString(""));
        node.put("dataSha256", sha);
        node.put("retrievedAt", snapshot.path("retrieved_at").asString(""));
        node.put("sourcePoints", snapshot.path("source_points").asInt(0));
        node.put("truncated", snapshot.path("truncated").asBoolean(false));
        return node;
    }

    /** 写可空字段：空值写 JSON null，避免字段缺失让读取方去猜。 */
    private static void putNullable(ObjectNode root, String field, String value) {
        if (value == null || value.isEmpty()) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

    /** 写字符串数组字段；values 为 null 时写空数组。 */
    private static void putArray(ObjectNode root, String field, List<String> values) {
        ArrayNode array = root.putArray(field);
        if (values != null) {
            values.forEach(array::add);
        }
    }

    /**
     * 报告的运行时元信息。plan.md §8 要求研究任务至少记录模型、Prompt 版本与 Agent 类型。
     *
     * @param model         模型名，来自模型实例本身而不是再读一遍配置
     * @param promptVersion 提示词版本，由提示词内容摘要得出
     * @param agentVersion  AgentScope 版本
     * @param attemptNo     第几次执行
     */
    public record Meta(String model, String promptVersion, String agentVersion, int attemptNo) {
    }

    /**
     * 便于日志与测试读的摘要，不入库。
     *
     * @param reportJson 报告 JSON，null 时返回 {@code report=null}
     * @return 一行文本：decision、快照是否绑定、是否有证据链
     */
    public static String summary(String reportJson) {
        if (reportJson == null) {
            return "report=null";
        }
        JsonNode root = JSON.readTree(reportJson);
        return "decision=" + root.path("decision").asString("")
            + " snapshotBound=" + root.path("snapshot").path("bound").asBoolean(false)
            + " evidence=" + root.path("evidenceAvailable").asBoolean(false);
    }

    /** BigDecimal 只在报告里出现一次，留个转换免得调用方各写各的。 */
    static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
