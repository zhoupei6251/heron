package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** data 闸：只判快照自身一致与占位符，不判事实真伪。 */
@DisplayName("数据看板：dataset 契约闸")
class DatasetContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ENABLED = Set.of("sina-gold-xau");

    private static String snapshot() {
        String body = "/*x*/ var _hf_XAU=(["
            + "{\"date\":\"2026-11-05\",\"close\":\"4091.55\"},"
            + "{\"date\":\"2026-11-06\",\"close\":\"4105.50\"},"
            + "{\"date\":\"2026-11-09\",\"close\":\"4110.04\"},"
            + "{\"date\":\"2026-11-10\",\"close\":\"4113.34\"}]);";
        return SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD, body,
            Instant.parse("2026-11-10T03:22:00Z"), 30);
    }

    private static JsonNode node(String json) {
        return JSON.readTree(json);
    }

    private static String mutate(String json, String from, String to) {
        assertTrue(json.contains(from), "fixture 里找不到要改的片段: " + from);
        return json.replace(from, to);
    }

    @Test
    @DisplayName("适配器产出的快照没有违例")
    void adapterSnapshotPasses() {
        assertEquals(List.of(), DatasetContract.violations(node(snapshot()), ENABLED));
    }

    @Test
    @DisplayName("facts 被改动后按字段报不一致")
    void tamperedFactsFail() {
        List<String> items = DatasetContract.violations(
            node(mutate(snapshot(), "\"change\":21.79", "\"change\":99.99")), ENABLED);
        assertTrue(items.stream().anyMatch(item -> item.contains("facts.change")), items.toString());
    }

    @Test
    @DisplayName("哈希被改动后单独报不一致")
    void tamperedHashFails() {
        String json = snapshot();
        String hash = node(json).path("data_sha256").asString();
        List<String> items = DatasetContract.violations(
            node(mutate(json, hash, "0".repeat(64))), ENABLED);
        assertTrue(items.stream().anyMatch(item -> item.contains("data_sha256")), items.toString());
    }

    @Test
    @DisplayName("未启用源、带 token 的 URL、未知字段都拒绝")
    void rejectsRegistryAndUrlAndUnknownFields() {
        assertTrue(DatasetContract.violations(node(snapshot()), Set.of("other")).stream()
            .anyMatch(item -> item.contains("source_key 未在注册表启用")));

        String url = node(snapshot()).path("source_url").asString();
        assertTrue(DatasetContract.violations(
            node(mutate(snapshot(), url, "https://x.test/a?token=1")), ENABLED).stream()
            .anyMatch(item -> item.contains("key/token")));

        assertTrue(DatasetContract.violations(
            node(mutate(snapshot(), "\"contract\":\"dataset/1\"", "\"contract\":\"dataset/1\",\"extra\":1")), ENABLED)
            .stream().anyMatch(item -> item.contains("未知字段 extra")));
    }

    @Test
    @DisplayName("时间窗与行数不一致会拒绝")
    void rejectsTimeRangeDrift() {
        List<String> items = DatasetContract.violations(
            node(mutate(snapshot(), "\"points\":4", "\"points\":3")), ENABLED);
        assertTrue(items.stream().anyMatch(item -> item.contains("time_range.points")), items.toString());
    }

    @Test
    @DisplayName("旁白只允许 facts 占位符，正文不许裸数字")
    void narrationRules() {
        JsonNode snapshot = node(snapshot());
        assertEquals(List.of(), DatasetContract.narrationViolations(snapshot,
            "金价收于 {{facts.last.value}} 美元，较前一日 {{facts.change}} 美元。"));

        List<String> digits = DatasetContract.narrationViolations(snapshot,
            "金价涨了 3% ，收于 {{facts.last.value}} 美元。");
        assertTrue(digits.contains("旁白正文含裸数字"), digits.toString());

        List<String> missing = DatasetContract.narrationViolations(snapshot, "金价 {{facts.last.price}}");
        assertTrue(missing.stream().anyMatch(item -> item.contains("无法解析")), missing.toString());
    }
}
