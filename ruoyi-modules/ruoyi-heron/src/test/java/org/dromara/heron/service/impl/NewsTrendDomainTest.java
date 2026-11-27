package org.dromara.heron.service.impl;

import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.service.impl.shared.production.NewsProductionDomain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 趋势线的领域口径：多一道闸、多一类产物、少一份「文章线」的宽松。
 *
 * <p>这里守的是三条底线：趋势版本必须十三道闸齐全并带 trend.json，
 * 看板线与文章线各用各的闸集，article 版本不能靠趋势的闸蒙过去。
 *
 * <p>之所以能给领域方法直接断言，是因为 {@code JsonUtils} 在静态初始化时要向 Spring 容器
 * 要全局 JsonMapper；不给容器，所有解析输入快照的方法都会连锁 NoClassDefFoundError。
 */
@DisplayName("趋势线：领域闸集与产物清单")
class NewsTrendDomainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void initializeJsonContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.refresh();
        new SpringUtils().setApplicationContext(context);
    }

    /** 领域校验这几条路径不碰持久层，所以依赖一律给 null。 */
    private static NewsProductionDomain domain() {
        return new NewsProductionDomain(null, null, null, null, null, null, null, null,
            new HeronProductionProperties(), null);
    }

    private static HeronVideoVersion version(String kind) {
        HeronVideoVersion version = new HeronVideoVersion();
        version.setId(11L);
        version.setWorkId(22L);
        version.setTaskId(33L);
        version.setOwnerId(44L);
        version.setInputSnapshot("{\"schemaVersion\":\"frozen/1\",\"kind\":\"" + kind + "\"}");
        return version;
    }

    private static HeronProductionTask task() {
        HeronProductionTask task = new HeronProductionTask();
        task.setId(33L);
        task.setVersionId(11L);
        task.setBizId(22L);
        task.setOwnerId(44L);
        task.setBizType(ProductionBizType.news_explainer.name());
        task.setAttemptNo(1);
        task.setRunToken("11111111-1111-1111-1111-111111111111");
        return task;
    }

    private static final Set<String> TREND_GATES = Set.of("schema", "evidence", "data", "trend", "audio",
        "audio_mix", "subtitles", "render", "decode", "visual", "aigc_metadata", "aigc_visible", "assets");
    private static final Set<String> DATA_BOARD_GATES = Set.of("schema", "evidence", "data", "audio",
        "audio_mix", "subtitles", "render", "decode", "visual", "aigc_metadata", "aigc_visible", "assets");
    private static final Set<String> ARTICLE_GATES = Set.of("schema", "evidence", "audio", "audio_mix",
        "subtitles", "render", "decode", "visual", "aigc_metadata", "aigc_visible", "assets");

    /** 报告形状：数组，一个闸一项，字段只允许 gate/inputLocator/passed/reason/measured。 */
    private static String checks(Set<String> gates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String gate : gates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gate", gate);
            item.put("inputLocator", "work." + gate);
            item.put("passed", true);
            item.put("reason", gate + " 全过");
            item.put("measured", Map.of("count", 1));
            list.add(item);
        }
        return JSON.writeValueAsString(list);
    }

    @Test
    @DisplayName("来源线取值：趋势不是看板，看板也不是文章")
    void kindMappingCoversThreeLines() {
        EditorialSelection trend = new EditorialSelection(EditorialSelection.TREND_INSIGHT, null,
            "sample-attention", false, "news-trend-9x16", "先立区间，再讲斜率", List.of(), null, null);
        EditorialSelection dataBoard = new EditorialSelection(EditorialSelection.DATA_BOARD, null,
            "sample-attention", false, "news-data-9x16", "复述数据", List.of(), null, null);
        EditorialSelection explainer = new EditorialSelection(EditorialSelection.NEWS_EXPLAINER, 1L, null,
            false, "news-quote-9x16", "解释一件事", List.of("7"), null, null);

        assertEquals("trend", NewsProductionDomain.kindOf(trend));
        assertEquals("data_board", NewsProductionDomain.kindOf(dataBoard));
        assertEquals("article", NewsProductionDomain.kindOf(explainer));
        assertEquals("article", NewsProductionDomain.kindOf(null));

        assertTrue(trend.trendInsight());
        assertTrue(trend.datasetLine());
        assertTrue(dataBoard.dataBoard());
        assertTrue(dataBoard.datasetLine());
        assertFalse(explainer.datasetLine());
    }

    @Test
    @DisplayName("趋势版本必须十三道闸齐全；看板线与文章线各用各的")
    void requiredGatesFollowsLine() {
        NewsProductionDomain domain = domain();

        assertEquals(TREND_GATES, domain.requiredGates(version("trend")));
        assertEquals(DATA_BOARD_GATES, domain.requiredGates(version("data_board")));
        assertEquals(ARTICLE_GATES, domain.requiredGates(version("article")));

        // 三套闸集互不相等：趋势比看板只多 trend，看板比文章只多 data
        assertTrue(TREND_GATES.containsAll(DATA_BOARD_GATES));
        assertEquals(DATA_BOARD_GATES.size() + 1, TREND_GATES.size());
        assertTrue(DATA_BOARD_GATES.containsAll(ARTICLE_GATES));
        assertEquals(ARTICLE_GATES.size() + 1, DATA_BOARD_GATES.size());
        assertTrue(domain.trend(version("trend")));
        assertTrue(domain.dataBoard(version("data_board")));
        assertFalse(domain.trend(version("data_board")));
        assertFalse(domain.trend(version("article")));
    }

    @Test
    @DisplayName("趋势报告缺 trend 闸就拒；看板的十二道闸蒙不过趋势")
    void trendChecksNeedTrendGate() {
        NewsProductionDomain domain = domain();
        HeronVideoVersion trendVersion = version("trend");

        domain.validateChecks(checks(TREND_GATES), trendVersion);

        HeronBusinessException missingTrend = assertThrows(HeronBusinessException.class,
            () -> domain.validateChecks(checks(DATA_BOARD_GATES), trendVersion));
        assertEquals("news_checks_incomplete", missingTrend.getCode());
        assertTrue(missingTrend.getMessage().contains("闸"), missingTrend.getMessage());

        // 文章版本的闸集更窄：带上 data/trend 反而算多余闸，允许；但缺 audio 必须拒
        HeronVideoVersion articleVersion = version("article");
        domain.validateChecks(checks(TREND_GATES), articleVersion);
        HeronBusinessException missingAudio = assertThrows(HeronBusinessException.class,
            () -> domain.validateChecks(checks(Set.of("schema", "evidence")), articleVersion));
        assertEquals("news_checks_incomplete", missingAudio.getCode());
    }

    @Test
    @DisplayName("趋势闸的实测值必须是数值或布尔，定位符不能带空格")
    void trendGateMeasurementsStayNumeric() {
        NewsProductionDomain domain = domain();
        String badValue = "[{\"gate\":\"trend\",\"inputLocator\":\"trend.json#verdict\",\"passed\":true,"
            + "\"reason\":\"判定已出\",\"measured\":{\"verdict\":\"sustained_up\"}}]";
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> domain.validateChecks(badValue, Set.of("trend")));
        assertEquals("news_checks_invalid", error.getCode());

        String badLocator = "[{\"gate\":\"trend\",\"inputLocator\":\"trend.json #verdict\",\"passed\":true,"
            + "\"reason\":\"判定已出\",\"measured\":{\"points\":12}}]";
        HeronBusinessException unsafe = assertThrows(HeronBusinessException.class,
            () -> domain.validateChecks(badLocator, Set.of("trend")));
        assertEquals("news_gate_failed", unsafe.getCode());
    }

    @Test
    @DisplayName("趋势版本要八类产物，少一类 trend 就拒")
    void trendManifestNeedsEightKinds() {
        NewsProductionDomain domain = domain();
        HeronVideoVersion version = version("trend");
        HeronProductionTask task = task();

        List<ProductionArtifactVo> dataBoardFiles = new ArrayList<>();
        for (String kind : List.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest",
            "dataset")) {
            dataBoardFiles.add(artifact(kind, dataBoardFiles.size() + 1));
        }

        HeronBusinessException missing = assertThrows(HeronBusinessException.class,
            () -> domain.validateManifest(version, task, dataBoardFiles, false));
        assertEquals("news_manifest_invalid", missing.getCode());
        assertTrue(missing.getMessage().contains("八类"), missing.getMessage());

        dataBoardFiles.add(artifact("trend", 8));
        domain.validateManifest(version, task, dataBoardFiles, false);

        // 同一份八类清单套到看板版本上就多了一类 trend，必须拒
        HeronVideoVersion dataBoardVersion = version("data_board");
        HeronBusinessException extra = assertThrows(HeronBusinessException.class,
            () -> domain.validateManifest(dataBoardVersion, task, dataBoardFiles, false));
        assertTrue(extra.getMessage().contains("七类"), extra.getMessage());
    }

    @Test
    @DisplayName("封面与方图允许进入受保护预览白名单")
    void coverArtifactsArePreviewable() {
        assertEquals("-cover.png", NewsProductionDomain.requirePreviewKind("cover"));
        assertEquals("-cover-thumb.png", NewsProductionDomain.requirePreviewKind("cover_thumb"));
    }

    @Test
    @DisplayName("文章产物可带一对封面，但少一张或只带一张都拒")
    void articleManifestAcceptsOnlyCompleteCoverPair() {
        NewsProductionDomain domain = domain();
        HeronVideoVersion version = version("article");
        HeronProductionTask task = task();
        List<ProductionArtifactVo> files = new ArrayList<>();
        for (String kind : List.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest")) {
            files.add(artifact(kind, files.size() + 1));
        }

        domain.validateManifest(version, task, files, false);
        files.add(artifact("cover", 7));
        HeronBusinessException single = assertThrows(HeronBusinessException.class,
            () -> domain.validateManifest(version, task, files, false));
        assertEquals("news_manifest_invalid", single.getCode());

        files.add(artifact("cover_thumb", 8));
        domain.validateManifest(version, task, files, false);

        List<ProductionArtifactVo> replaced = files.stream()
            .filter(file -> !List.of("contact_sheet", "asset_manifest").contains(file.kind())).toList();
        HeronBusinessException missingBase = assertThrows(HeronBusinessException.class,
            () -> domain.validateManifest(version, task, replaced, false));
        assertEquals("news_manifest_invalid", missingBase.getCode());
    }

    @Test
    @DisplayName("趋势产物的存储键必须属于本版本，不能混进别的版本")
    void trendArtifactMustBelongToThisVersion() {
        NewsProductionDomain domain = domain();
        HeronVideoVersion version = version("trend");
        HeronProductionTask task = task();
        List<ProductionArtifactVo> files = new ArrayList<>();
        for (String kind : List.of("mp4", "contact_sheet", "script", "subtitles", "checks", "asset_manifest",
            "dataset", "trend")) {
            files.add(artifact(kind, files.size() + 1));
        }
        files.set(7, new ProductionArtifactVo(files.get(7).artifactId(), "trend",
            "videos/22/v99/a1-11111111-1111-1111-1111-111111111111/" + files.get(7).artifactId() + ".json",
            1024, "a".repeat(64), "application/json"));

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> domain.validateManifest(version, task, files, false));
        assertEquals("news_artifact_association", error.getCode());
    }

    private static ProductionArtifactVo artifact(String kind, int index) {
        String id = String.format("%08d-1111-1111-1111-111111111111", index);
        String extension = switch (kind) {
            case "mp4" -> "mp4";
            case "contact_sheet", "cover", "cover_thumb" -> "png";
            default -> "json";
        };
        String mediaType = switch (kind) {
            case "mp4" -> "video/mp4";
            case "contact_sheet", "cover", "cover_thumb" -> "image/png";
            default -> "application/json";
        };
        String key = "videos/22/v11/a1-11111111-1111-1111-1111-111111111111/" + id + "." + extension;
        return new ProductionArtifactVo(id, kind, key, 1024, "a".repeat(64), mediaType);
    }
}
