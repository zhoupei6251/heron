package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("数据分镜：画面重点来自真实事实引用")
class DatasetVisualFocusTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode DATASET = JSON.readTree("""
        {"shape":"wide","facts":{"first":{"value":100},"last":{"value":110},
        "min":{"value":98},"max":{"value":112},"change":10,"change_pct":10,
        "leader":{"label":"甲公司","value":80},"spread":60,
        "items":[{"label":"甲公司","value":80},{"label":"乙公司","value":20}]}}
        """);

    private static JsonNode scene(String body) {
        return JSON.readTree(JSON.writeValueAsString(java.util.Map.of("lead", "本段结论", "body", body)));
    }

    @Test
    @DisplayName("最新值、区间变化和高低点生成不同聚焦，引用去重保序")
    void derivesModesFromReferences() {
        assertEquals("latest", DatasetVisualFocus.forScene(scene("最新 {{facts.last.value}}"), DATASET).mode());
        var change = DatasetVisualFocus.forScene(scene("变化 {{facts.change}}，幅度 {{facts.change_pct}}，"
            + "再次引用 {{facts.change}}"), DATASET);
        assertEquals("change", change.mode());
        assertEquals(List.of("change", "change_pct"), change.refs());
        assertEquals("range", DatasetVisualFocus.forScene(scene("最高 {{facts.max.value}}，"
            + "最低 {{facts.min.value}}"), DATASET).mode());
    }

    @Test
    @DisplayName("排行定位使用实体名称，不把源行号当作排序后的画面行号")
    void anchorsRankedEntitiesByLabel() {
        var item = DatasetVisualFocus.forScene(scene("乙公司数值 {{facts.items.1.value}}"), DATASET);
        assertEquals("item", item.mode());
        assertEquals(List.of("乙公司"), item.labels());
        assertEquals("leader", DatasetVisualFocus.forScene(scene("榜首 {{facts.leader.value}}"), DATASET).mode());
        assertEquals("comparison", DatasetVisualFocus.forScene(scene("头尾相差 {{facts.spread}}"), DATASET).mode());
    }

    @Test
    @DisplayName("无引用展示全貌；不存在的引用拒绝生成聚焦")
    void neverInventsEvidence() {
        var overview = DatasetVisualFocus.forScene(scene("注意这份数据的统计范围。"), DATASET);
        assertEquals("overview", overview.mode());
        assertEquals(List.of(), overview.refs());
        assertEquals("dataset_focus_invalid", assertThrows(HeronBusinessException.class,
            () -> DatasetVisualFocus.forScene(scene("未知 {{facts.missing.value}}"), DATASET)).getCode());
    }
}
