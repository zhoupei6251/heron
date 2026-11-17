package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 研究候选的自动准入：逐源开启 + 灰度准入 + 可下架。
 *
 * <p>这条闸的默认值比它的功能重要：默认必须是「不允许」。
 * 一个默认放行的实现，等于把所有 Agent 衍生的选题一次性推进自动发布。
 */
@DisplayName("研究候选自动准入：默认关闭，需显式开启且探测 ok")
class NewsAutoResearchAdmissionTest {

    private static HeronDataSource source(String key, String enabled, String autoEnabled, String probe) {
        HeronDataSource row = new HeronDataSource();
        row.setSourceKey(key);
        row.setEnabled(enabled);
        row.setAutoEnabled(autoEnabled);
        row.setProbeStatus(probe);
        return row;
    }

    @Test
    @DisplayName("默认不放行：auto_enabled 为 1 或不填都不进")
    void defaultsToDenied() {
        Set<String> keys = NewsAutoService.autoResearchSources(List.of(
            source("a", "0", "1", "ok"),
            source("b", "0", null, "ok")));

        assertTrue(keys.isEmpty(), "默认必须是拒绝，实际放行了：" + keys);
    }

    @Test
    @DisplayName("显式开启且探测 ok 才放行")
    void admitsOnlyExplicitlyEnabledAndHealthy() {
        Set<String> keys = NewsAutoService.autoResearchSources(List.of(
            source("ok-source", "0", "0", "ok"),
            source("blocked-source", "0", "0", "blocked")));

        assertEquals(Set.of("ok-source"), keys, "探测没过的源不该跟着放行");
    }

    /** 「灰度准入」要能在探测回退时立刻收回，否则一个坏源会持续自动发布。 */
    @Test
    @DisplayName("源探测一旦回退，准入立即失效")
    void revokesWhenProbeDegrades() {
        HeronDataSource row = source("a", "0", "0", "ok");
        assertTrue(NewsAutoService.autoResearchSources(List.of(row)).contains("a"));

        row.setProbeStatus("blocked");

        assertFalse(NewsAutoService.autoResearchSources(List.of(row)).contains("a"),
            "探测回退后仍放行，等于没有灰度");
    }
}
