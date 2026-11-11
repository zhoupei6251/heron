package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 抓取前的准入判定：只测分支，不碰库也不碰网络。 */
@DisplayName("数据看板：源准入与适配器选择")
class DatasetFetchServiceTest {

    private static HeronDataSource source(String enabled, String probe) {
        HeronDataSource source = new HeronDataSource();
        source.setSourceKey("sina-gold-xau");
        source.setEnabled(enabled);
        source.setProbeStatus(probe);
        return source;
    }

    private static final DatasetFetchService SERVICE = new DatasetFetchService(null, null);

    @Test
    @DisplayName("已登记的源各选出自己的适配器")
    void adapterForKnownSources() {
        assertEquals("sina-gold-xau", SERVICE.adapterFor("sina-gold-xau").sourceKey());
        assertEquals("sina-oil-cl", SERVICE.adapterFor("sina-oil-cl").sourceKey());
        assertEquals("eastmoney-sse", SERVICE.adapterFor("eastmoney-sse").sourceKey());
        assertEquals("frankfurter", SERVICE.adapterFor("frankfurter").sourceKey());
        assertEquals("eastmoney-cpi", SERVICE.adapterFor("eastmoney-cpi").sourceKey());
        assertEquals("metno-locationforecast", SERVICE.adapterFor("metno-locationforecast").sourceKey());
    }

    @Test
    @DisplayName("没有适配器的源直接拒绝，不在运行期现编")
    void adapterForUnknownSource() {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> SERVICE.adapterFor("maoyan-daily"));
        assertTrue(error.getMessage().contains("没有适配器"), error.getMessage());
    }

    @Test
    @DisplayName("未登记、未启用、探测未过都拒绝")
    void requireReadyRejects() {
        assertTrue(assertThrows(HeronBusinessException.class,
            () -> DatasetFetchService.requireReady(null, "sina-gold-xau")).getMessage().contains("没有该源"));
        assertTrue(assertThrows(HeronBusinessException.class,
            () -> DatasetFetchService.requireReady(source("1", "ok"), "sina-gold-xau")).getMessage().contains("未启用"));
        assertTrue(assertThrows(HeronBusinessException.class,
            () -> DatasetFetchService.requireReady(source("0", "blocked"), "sina-gold-xau")).getMessage().contains("不是 ok"));
    }

    @Test
    @DisplayName("启用且探测为 ok 时放行")
    void requireReadyPasses() {
        assertDoesNotThrow(() -> DatasetFetchService.requireReady(source("0", "ok"), "sina-gold-xau"));
    }
}
