package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.MediaAsset;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.mapper.MediaAssetMapper;
import org.dromara.heron.support.MediaAssetTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("场景选材：真实素材过闸，删除后同场景退化为纯文字")
class MediaAssetSelectionServiceTest {

    private final MediaAssetMapper assets = mock(MediaAssetMapper.class);
    private final MediaAssetStorage storage = mock(MediaAssetStorage.class);
    private final MediaAssetServiceImpl service = new MediaAssetServiceImpl(assets, storage);

    @BeforeAll
    static void initializeJson() {
        MediaAssetTestSupport.initialize();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "media-asset"), MediaAsset.class);
    }

    @Test
    void selectedAndFallbackGatesPassExistingChecksValidation() {
        when(assets.selectById(7L)).thenReturn(eligibleAsset());
        MediaAssetSelectionVo selected = service.selectForScene(request().assetId(7L).build());
        MediaAssetSelectionVo fallback = service.selectForScene(request().sceneType("chart").build());
        NewsProductionDomain domain = new NewsProductionDomain(null, null, null, null, null, null, null, null,
            new HeronProductionProperties(), null);

        domain.validateChecks(JsonUtils.toJsonString(List.of(selected.getGate())), Set.of("assets"));
        domain.validateChecks(JsonUtils.toJsonString(List.of(fallback.getGate())), Set.of("assets"));

        assertEquals("scene:scene-2", selected.getGate().getInputLocator());
        assertEquals("scene:scene-2", fallback.getGate().getInputLocator());
        assertEquals("7", selected.getGate().getMeasured().get("assetId"));
        assertEquals(true, selected.getGate().getMeasured().get("licenseChecked"));
        assertEquals(true, fallback.getGate().getMeasured().get("fallbackToText"));
        assertEquals(false, fallback.getGate().getMeasured().get("assetPresent"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSceneIds")
    void invalidSceneIdIsRejectedBeforeAssetQueryOrGateCreation(String label, String sceneId) {
        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.selectForScene(request().sceneId(sceneId).assetId(7L).build()));

        assertEquals(400, error.getStatus(), label);
        assertEquals("media_asset_scene_invalid", error.getCode(), label);
        verifyNoInteractions(assets);
    }

    @Test
    void maximumSceneIdProducesLocatorAcceptedByExistingChecks() {
        String sceneId = "a".repeat(250);
        when(assets.findForScene(any())).thenReturn(null);

        MediaAssetSelectionVo result = service.selectForScene(request().sceneId(sceneId).build());
        NewsProductionDomain domain = new NewsProductionDomain(null, null, null, null, null, null, null, null,
            new HeronProductionProperties(), null);

        assertEquals(256, result.getGate().getInputLocator().length());
        assertTrue(result.getGate().isPassed());
        domain.validateChecks(JsonUtils.toJsonString(List.of(result.getGate())), Set.of("assets"));
    }

    private static Stream<Arguments> invalidSceneIds() {
        return Stream.of(
            Arguments.of("null", null),
            Arguments.of("empty", ""),
            Arguments.of("blank", " "),
            Arguments.of("slash", "scene/1"),
            Arguments.of("backslash", "scene\\1"),
            Arguments.of("non-ascii", "场景-2"),
            Arguments.of("trailing space", "scene-2 "),
            Arguments.of("251 characters", "a".repeat(251)),
            Arguments.of("256 characters", "a".repeat(256))
        );
    }

    @Test
    void selectedMaterialUsesSceneImageVariableAndPassesAssetsGate() {
        MediaAsset asset = eligibleAsset();
        when(assets.selectById(7L)).thenReturn(asset);

        MediaAssetSelectionVo result = service.selectForScene(request().assetId(7L).build());

        assertEquals("SELECTED", result.getStatus());
        assertEquals("scene2_image", result.getImageVariable());
        assertEquals("https://oss.example.test/city.png", result.getImageValue());
        assertEquals(7L, result.getAssetId());
        assertEquals("CC_BY", result.getLicenseCode());
        assertTrue(result.getGate().isPassed());
        assertEquals(false, result.getGate().getMeasured().get("fallbackToText"));
        assertEquals(true, result.getGate().getMeasured().get("attributionRequired"));
        assertEquals(true, result.getGate().getMeasured().get("attributionReady"));
    }

    @Test
    void deletingMaterialMakesSameSceneTextOnlyAndStillPassesGate() {
        MediaAsset asset = eligibleAsset();
        when(assets.selectById(7L)).thenReturn(asset);
        when(assets.selectByIds(anyCollection())).thenAnswer(invocation -> java.util.List.of(asset));
        when(assets.deleteByIds(anyCollection())).thenAnswer(invocation -> {
            when(assets.selectById(7L)).thenReturn(null);
            when(assets.selectByIds(anyCollection())).thenReturn(java.util.List.of());
            return 1;
        });
        MediaAssetSelectionBo request = request().assetId(7L).build();
        assertEquals("SELECTED", service.selectForScene(request).getStatus());

        assertTrue(service.deleteWithValidByIds(java.util.List.of(7L)));

        MediaAssetSelectionVo result = service.selectForScene(request);
        assertEquals("TEXT_ONLY", result.getStatus());
        assertEquals("scene2_image", result.getImageVariable());
        assertEquals("", result.getImageValue());
        assertEquals("asset_missing", result.getReasonCode());
        assertNull(result.getAssetId());
        assertTrue(result.getGate().isPassed());
        assertEquals(true, result.getGate().getMeasured().get("fallbackToText"));
    }

    @Test
    void missingCcAttributionFallsBackAndInvalidExplicitSelectionIsRejected() {
        MediaAsset asset = eligibleAsset();
        asset.setAttribution(" ");
        when(assets.selectById(7L)).thenReturn(asset);
        MediaAssetSelectionVo fallback = service.selectForScene(request().assetId(7L).build());
        assertEquals("TEXT_ONLY", fallback.getStatus());
        assertEquals("asset_incomplete", fallback.getReasonCode());
        asset.setAttribution("示例作者");
        asset.setLicenseCode("CC_BY_SA");
        MediaAssetSelectionVo shareAlike = service.selectForScene(request().assetId(7L).build());
        assertEquals("SELECTED", shareAlike.getStatus());
        assertEquals(true, shareAlike.getGate().getMeasured().get("attributionRequired"));

        assertEquals("media_asset_orientation_invalid", assertThrows(HeronBusinessException.class,
            () -> service.selectForScene(request().orientation("diagonal").build())).getCode());
        assertEquals("media_asset_id_invalid", assertThrows(HeronBusinessException.class,
            () -> service.selectForScene(request().assetId(-1L).build())).getCode());
    }

    @Test
    void unknownLicenseIsRejectedAgainAtSelection() {
        MediaAsset asset = eligibleAsset();
        asset.setLicenseCode("UNKNOWN");
        when(assets.selectById(7L)).thenReturn(asset);

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> service.selectForScene(request().assetId(7L).build()));

        assertEquals(409, error.getStatus());
        assertEquals("media_asset_license_rejected", error.getCode());
    }

    @Test
    void missingOssPointerFallsBackInsteadOfRenderingEmptyFrame() {
        MediaAsset asset = eligibleAsset();
        asset.setOssKey(null);
        when(assets.selectById(7L)).thenReturn(asset);

        MediaAssetSelectionVo result = service.selectForScene(request().assetId(7L).build());

        assertEquals("TEXT_ONLY", result.getStatus());
        assertEquals("asset_incomplete", result.getReasonCode());
        assertEquals("", result.getImageValue());
        assertTrue(result.getGate().isPassed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteAssets")
    void incompleteProductionMetadataFallsBack(String label, Consumer<MediaAsset> mutation) {
        MediaAsset asset = eligibleAsset();
        mutation.accept(asset);
        when(assets.selectById(7L)).thenReturn(asset);

        MediaAssetSelectionVo result = service.selectForScene(request().assetId(7L).build());

        assertEquals("TEXT_ONLY", result.getStatus(), label);
        assertEquals("asset_incomplete", result.getReasonCode(), label);
        assertEquals("", result.getImageValue(), label);
        assertTrue(result.getGate().isPassed(), label);
    }

    private static Stream<Arguments> incompleteAssets() {
        return Stream.of(
            Arguments.of("missing source", (Consumer<MediaAsset>) asset -> asset.setSourceUri(" ")),
            Arguments.of("invalid source", (Consumer<MediaAsset>) asset -> asset.setSourceUri("file:///tmp/city.png")),
            Arguments.of("missing license evidence", (Consumer<MediaAsset>) asset -> asset.setLicenseEvidenceUri(null)),
            Arguments.of("invalid license evidence", (Consumer<MediaAsset>) asset -> asset.setLicenseEvidenceUri("relative")),
            Arguments.of("missing media type", (Consumer<MediaAsset>) asset -> asset.setMediaType(null)),
            Arguments.of("unsupported media type", (Consumer<MediaAsset>) asset -> asset.setMediaType("image/gif")),
            Arguments.of("missing size", (Consumer<MediaAsset>) asset -> asset.setSizeBytes(null)),
            Arguments.of("non-positive size", (Consumer<MediaAsset>) asset -> asset.setSizeBytes(0L)),
            Arguments.of("short hash", (Consumer<MediaAsset>) asset -> asset.setContentSha256("a".repeat(63))),
            Arguments.of("non-hex hash", (Consumer<MediaAsset>) asset -> asset.setContentSha256("g".repeat(64)))
        );
    }

    @Test
    void automaticSelectionWithoutCandidatesReturnsNoMatchingAsset() {
        when(assets.findForScene(any())).thenReturn(null);

        MediaAssetSelectionVo result = service.selectForScene(request().build());

        assertEquals("TEXT_ONLY", result.getStatus());
        assertEquals("no_matching_asset", result.getReasonCode());
        verify(assets, never()).selectById(anyLong());
    }

    @Test
    void sceneWithoutImageCapabilityFallsBackWithoutQueryingAssets() {
        MediaAssetSelectionVo result = service.selectForScene(
            request().sceneType("chart").assetId(7L).build());

        assertEquals("TEXT_ONLY", result.getStatus());
        assertEquals("scene_type_not_image", result.getReasonCode());
        verifyNoInteractions(assets);
    }

    @Test
    void sceneNeedSelectsMatchingMaterialAndRechecksLicense() {
        MediaAsset asset = eligibleAsset();
        when(assets.findForScene(any())).thenReturn(asset);

        MediaAssetSelectionVo result = service.selectForScene(request().build());

        assertEquals("SELECTED", result.getStatus());
        assertEquals(7L, result.getAssetId());
        verify(assets).findForScene(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void automaticSelectionQueryFiltersLicenseIntegrityOrientationAndAllKeywords() {
        MediaAssetMapper queryMapper = mock(MediaAssetMapper.class, CALLS_REAL_METHODS);
        doReturn(eligibleAsset()).when(queryMapper).selectOne(any());
        MediaAssetSelectionBo request = request().build();

        queryMapper.findForScene(request);

        ArgumentCaptor<LambdaQueryWrapper<MediaAsset>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(queryMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("asset_kind ="));
        assertTrue(sql.contains("license_code IN"));
        assertTrue(sql.contains("source_uri IS NOT NULL"));
        assertTrue(sql.contains("license_evidence_uri IS NOT NULL"));
        assertTrue(sql.contains("media_type IN"));
        assertTrue(sql.contains("size_bytes >"));
        assertTrue(sql.contains("orientation ="));
        assertTrue(sql.contains("tags LIKE"));
        assertTrue(sql.contains("LIMIT 1"));
        assertTrue(captor.getValue().getParamNameValuePairs().values().containsAll(
            org.dromara.heron.domain.MediaAssetLicensePolicy.ALLOWED));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("PORTRAIT"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("%城市%"));
    }

    private static MediaAssetSelectionBo.MediaAssetSelectionBoBuilder request() {
        return MediaAssetSelectionBo.builder()
            .sceneId("scene-2")
            .sceneNumber(2)
            .sceneType("image")
            .orientation("PORTRAIT")
            .keywords(java.util.List.of("城市"));
    }

    private static MediaAsset eligibleAsset() {
        MediaAsset asset = new MediaAsset();
        asset.setId(7L);
        asset.setName("城市夜景");
        asset.setAssetKind("IMAGE");
        asset.setSourceUri("https://source.example.test/city");
        asset.setLicenseCode("CC_BY");
        asset.setLicenseEvidenceUri("https://license.example.test/cc-by");
        asset.setAttribution("示例作者");
        asset.setTags("城市,夜景");
        asset.setWidth(1080);
        asset.setHeight(1920);
        asset.setOrientation("PORTRAIT");
        asset.setContentSha256("a".repeat(64));
        asset.setSizeBytes(1024L);
        asset.setMediaType("image/png");
        asset.setOssConfigKey("qiniu");
        asset.setOssKey("heron/media/city.png");
        asset.setOssUrl("https://oss.example.test/city.png");
        asset.setDelFlag("0");
        return asset;
    }
}
