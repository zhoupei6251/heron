package org.dromara.heron.agentscope.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 报告要解决的是「下游能不能稳定取出结论」和「结论基于哪一份数据」。
 * 前者靠结构化，后者靠快照标识——只留时间戳等于说「那天取过」，证明不了取的是哪一份。
 */
@DisplayName("结构化研究报告：结论、风险与快照绑定")
class ResearchReportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ResearchReport.Meta META =
        new ResearchReport.Meta("deepseek-v4.1-flash-economy", "abc123def456", "agentscope-2.0.4", 1);

    private static ResearchFindings submitted() {
        ResearchFindings findings = new ResearchFindings();
        findings.record("reject", "独立信息量为零",
            "人民币对欧元走强", BigDecimal.valueOf(0.72),
            List.of("DKK/CNY 是 EUR/CNY 的机械镜像", "30 点比值恒定在 7.474"),
            List.of("该源只有 8 个点，无可独立验证的基线"),
            List.of("frankfurter-dkkcny"));
        return findings;
    }

    private static JsonNode snapshot() {
        return JSON.readTree("""
            {"contract":"dataset/1","shape":"wide","cadence":"daily",
             "retrieved_at":"2026-11-13T00:00:00Z","data_sha256":"abc123",
             "source_points":30,"truncated":false,"points":[]}
            """);
    }

    @Test
    @DisplayName("结论、要点、风险都进结构化字段，不是一段自然语言")
    void structuresFindings() {
        JsonNode root = JSON.readTree(ResearchReport.build(submitted(), null, snapshot(), META));

        assertEquals("REJECT", root.path("decision").asString());
        assertTrue(root.path("submitted").asBoolean());
        assertEquals("独立信息量为零", root.path("reason").asString());
        assertEquals("人民币对欧元走强", root.path("angle").asString());
        assertEquals("0.72", root.path("confidence").asString());
        assertEquals(2, root.path("keyInsights").size());
        assertEquals(1, root.path("riskWarnings").size());
        assertEquals("frankfurter-dkkcny", root.path("cited").get(0).asString());
    }

    @Test
    @DisplayName("快照绑定带上 sha256 与取数时间，两个都要")
    void bindsSnapshot() {
        JsonNode snap = JSON.readTree(ResearchReport.build(submitted(), null, snapshot(), META))
            .path("snapshot");

        assertTrue(snap.path("bound").asBoolean());
        assertEquals("abc123", snap.path("dataSha256").asString());
        assertEquals("2026-11-13T00:00:00Z", snap.path("retrievedAt").asString(), "只留 hash 不知道什么时候取的");
        assertEquals(30, snap.path("sourcePoints").asInt());
    }

    @Test
    @DisplayName("没有快照时 bound=false，不假装绑定过")
    void unboundWithoutSnapshot() {
        JsonNode snap = JSON.readTree(ResearchReport.build(submitted(), null, null, META))
            .path("snapshot");

        assertFalse(snap.path("bound").asBoolean());
        assertEquals("", snap.path("dataSha256").asString());
    }

    @Test
    @DisplayName("快照缺 sha256 时也算没绑定：没有版本标识就复核不了")
    void unboundWithoutHash() {
        JsonNode noHash = JSON.readTree("{\"contract\":\"dataset/1\",\"retrieved_at\":\"2026-11-13\"}");

        assertFalse(JSON.readTree(ResearchReport.build(submitted(), null, noHash, META))
            .path("snapshot").path("bound").asBoolean());
    }

    @Test
    @DisplayName("模型没交结论：decision 为 null，不编一个默认值")
    void noSubmissionStaysNull() {
        JsonNode root = JSON.readTree(ResearchReport.build(new ResearchFindings(), null, snapshot(), META));

        assertFalse(root.path("submitted").asBoolean());
        assertTrue(root.path("decision").isNull(), "没判断就写 null，不要给一个看起来像结论的默认值");
        assertEquals(0, root.path("keyInsights").size());
    }

    @Test
    @DisplayName("元信息记录模型、提示词版本与 Agent 版本")
    void recordsMeta() {
        JsonNode meta = JSON.readTree(ResearchReport.build(submitted(), null, snapshot(), META)).path("meta");

        assertEquals("deepseek-v4.1-flash-economy", meta.path("model").asString());
        assertEquals("abc123def456", meta.path("promptVersion").asString());
        assertEquals("agentscope-2.0.4", meta.path("agentVersion").asString());
        assertEquals(1, meta.path("attemptNo").asInt());
    }

    @Test
    @DisplayName("证据链挂在报告里，同时标出有没有")
    void embedsEvidence() {
        String evidence = "{\"version\":1,\"complete\":true,\"unverifiedCitations\":[]}";

        JsonNode withEvidence = JSON.readTree(ResearchReport.build(submitted(), evidence, snapshot(), META));
        assertTrue(withEvidence.path("evidenceAvailable").asBoolean());
        assertTrue(withEvidence.path("evidence").path("complete").asBoolean());

        JsonNode without = JSON.readTree(ResearchReport.build(submitted(), null, snapshot(), META));
        assertFalse(without.path("evidenceAvailable").asBoolean());
    }
}
