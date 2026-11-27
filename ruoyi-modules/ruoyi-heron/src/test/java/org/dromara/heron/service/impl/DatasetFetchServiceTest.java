package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.service.impl.lane.databoard.DatasetAdapter;
import org.dromara.heron.service.impl.lane.databoard.DatasetFetchService;
import org.dromara.heron.service.impl.shared.source.SinaFuturesDatasetAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

    @Test
    @DisplayName("生产公开入口拒绝 blocked 源，不能绕过健康闸进入适配器")
    void productionRejectsBlockedSourceBeforeFetching() {
        HeronDataSourceMapper mapper = mock(HeronDataSourceMapper.class);
        when(mapper.selectByKey("sina-gold-xau")).thenReturn(source("0", "blocked"));
        DatasetAdapter adapter = mock(DatasetAdapter.class);
        DatasetFetchService service = new DatasetFetchService(mapper, null) {
            @Override
            public DatasetAdapter adapterFor(String sourceKey) {
                return adapter;
            }
        };

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.fetch(null, "sina-gold-xau", 30));

        assertEquals("dataset_source_blocked", error.getCode());
        verifyNoInteractions(adapter);
    }

    @Test
    @DisplayName("分析公开入口允许探测 blocked 源，合格快照令源恢复健康")
    void analysisCanRecoverBlockedSource() {
        HeronDataSourceMapper mapper = mock(HeronDataSourceMapper.class);
        when(mapper.selectByKey("sina-gold-xau")).thenReturn(source("0", "blocked"));
        when(mapper.enabledKeys()).thenReturn(List.of("sina-gold-xau"));
        DatasetAdapter adapter = mock(DatasetAdapter.class);
        when(adapter.shape()).thenReturn("wide");
        when(adapter.fetch(30)).thenReturn(SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD,
            "var _hf_XAU=([{\"date\":\"2026-11-09\",\"close\":\"4110.04\"},"
                + "{\"date\":\"2026-11-10\",\"close\":\"4113.34\"}]);", Instant.now(), 30));
        DatasetFetchService service = new DatasetFetchService(mapper, null) {
            @Override
            public DatasetAdapter adapterFor(String sourceKey) {
                return adapter;
            }
        };

        JsonNode snapshot = service.snapshot("sina-gold-xau", 30);

        assertEquals(2, snapshot.path("rows").size());
        verify(mapper).markHealthy(eq("sina-gold-xau"), any());
    }

    @Test
    @DisplayName("分析不受 probe 状态限制：blocked 的源也要能探一次，否则永远恢复不了")
    void requireReadyIgnoresProbeForAnalysis() {
        assertDoesNotThrow(() -> DatasetFetchService.requireReady(source("0", "blocked"), "sina-gold-xau", true));
        assertThrows(HeronBusinessException.class,
            () -> DatasetFetchService.requireReady(source("1", "ok"), "sina-gold-xau", true),
            "未启用仍然拒绝，绕过只针对探测状态");
    }

    @Test
    @DisplayName("健康状态：连续失败够多才 blocked，单次抖动只留计数")
    void statusAfterFailureEscalates() {
        assertEquals("ok", DatasetFetchService.statusAfterFailure(1));
        assertEquals("ok", DatasetFetchService.statusAfterFailure(2));
        assertEquals("blocked", DatasetFetchService.statusAfterFailure(3));
        assertEquals("blocked", DatasetFetchService.statusAfterFailure(10));
    }
}
