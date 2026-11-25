package org.dromara.heron.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.sun.net.httpserver.HttpServer;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetGateVo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.service.IMediaAssetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V5 selector、OSS 下载、文字回退与返工未改屏复用集成。 */
@DisplayName("生产管线：V5 素材选择与返工复用")
class NewsProductionPipelineMediaAssetIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    @DisplayName("SELECTED 从真实 HTTP OSS 指针下载、校验指纹尺寸并替换 imageValue")
    void downloadsSelectedOssImageAndReplacesImageValue(@TempDir Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset.png", exchange -> {
            exchange.sendResponseHeaders(200, PNG.length);
            exchange.getResponseBody().write(PNG);
            exchange.close();
        });
        server.start();
        try {
            IMediaAssetService assets = mock(IMediaAssetService.class);
            MediaAssetSelectionVo selected = selection("SELECTED", "scene_001", 1);
            selected.setImageValue("http://127.0.0.1:" + server.getAddress().getPort() + "/asset.png");
            selected.setContentSha256(DigestUtil.sha256Hex(PNG));
            selected.setWidth(1);
            selected.setHeight(1);
            when(assets.selectForScene(any())).thenReturn(selected);
            NewsProductionPipelineSceneReworkTest.Fixture fixture =
                new NewsProductionPipelineSceneReworkTest.Fixture(root, assets);
            Path work = fixture.artifacts.prepareWorkDirectory(fixture.run);
            List<MediaAssetSelectionVo> selections = new ArrayList<>();

            Map<String, String> images = fixture.pipeline.resolveSceneAssets(work,
                JSON.readTree("{\"scene_id\":\"scene_001\",\"scene_type\":\"image\"}"), 1,
                JSON.readTree("{}"), selections);

            assertEquals("scene1.png", images.get("1"));
            assertEquals("scene1.png", selected.getImageValue());
            assertEquals(DigestUtil.sha256Hex(PNG), DigestUtil.sha256Hex(Files.readAllBytes(work.resolve("scene1.png"))));
            assertEquals(1, selections.size());
            assertEquals("SELECTED", selections.getFirst().getStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("合法 visual.mediaAssetId 先过稿件契约，再真实传入 selector 并选中指定素材")
    void contractSelectionConsumesDeclaredMediaAssetId(@TempDir Path root) throws Exception {
        JsonNode script = JSON.readTree("""
            {"source_url":"https://example.com/a","fetched_at":"2026-11-24","body_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "title":"示例标题","closing":"结论","source_name":"示例站","scenes":[
              {"scene_id":"scene_001","lead":"第一屏","body":"正文一","duration_s":15,"scene_type":"image",
               "visual":{"mediaAssetId":"42","orientation":"PORTRAIT","keywords":["城市"]}},
              {"scene_id":"scene_002","lead":"第二屏","body":"正文二","duration_s":15,"scene_type":"text"},
              {"scene_id":"scene_003","lead":"第三屏","body":"正文三","duration_s":15,"scene_type":"text"}]}
            """);
        assertTrue(NewsScriptContract.violations(script).isEmpty());
        ObjectNode invalid = (ObjectNode) script.deepCopy();
        ObjectNode invalidVisual = (ObjectNode) invalid.path("scenes").get(0).path("visual");
        invalidVisual.put("mediaAssetId", "not-a-number");
        assertFalse(NewsScriptContract.violations(invalid).isEmpty());
        invalidVisual.put("mediaAssetId", "42").put("orientation", "diagonal");
        assertFalse(NewsScriptContract.violations(invalid).isEmpty());
        IMediaAssetService assets = mock(IMediaAssetService.class);
        MediaAssetSelectionVo selected = selection("SELECTED", "scene_001", 1);
        selected.setImageValue("https://oss.example.test/42.png");
        selected.setContentSha256(DigestUtil.sha256Hex(PNG));
        selected.setWidth(1); selected.setHeight(1);
        when(assets.selectForScene(any())).thenReturn(selected);
        var fixture = new NewsProductionPipelineSceneReworkTest.Fixture(root, assets);
        NewsProductionPipeline pipeline = spy(fixture.pipeline);
        doAnswer(invocation -> PNG).when(pipeline).downloadSelectedAsset(any());

        Map<String, String> images = pipeline.resolveSceneAssets(fixture.artifacts.prepareWorkDirectory(fixture.run),
            script.path("scenes").get(0), 1, JSON.readTree("{}"), new ArrayList<>());
        assertEquals("scene1.png", images.get("1"));
        assertEquals("SELECTED", selected.getStatus());

        org.mockito.ArgumentCaptor<MediaAssetSelectionBo> request = org.mockito.ArgumentCaptor.forClass(MediaAssetSelectionBo.class);
        verify(assets).selectForScene(request.capture());
        assertEquals(42L, request.getValue().assetId());
        assertEquals("PORTRAIT", request.getValue().orientation());
        assertEquals(List.of("城市"), request.getValue().keywords());
    }

    @Test
    @DisplayName("真实 HTTP 404 将 SELECTED 素材退回纯文字，不升级为整片失败")
    void http404FallsBackToTextOnly(@TempDir Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.png", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            IMediaAssetService assets = mock(IMediaAssetService.class);
            MediaAssetSelectionVo selected = selection("SELECTED", "scene_001", 1);
            selected.setImageValue("http://127.0.0.1:" + server.getAddress().getPort() + "/missing.png");
            when(assets.selectForScene(any())).thenReturn(selected);
            var fixture = new NewsProductionPipelineSceneReworkTest.Fixture(root, assets);
            List<MediaAssetSelectionVo> selections = new ArrayList<>();

            Map<String, String> images = fixture.pipeline.resolveSceneAssets(fixture.artifacts.prepareWorkDirectory(fixture.run),
                JSON.readTree("{\"scene_id\":\"scene_001\",\"scene_type\":\"image\"}"), 1,
                JSON.readTree("{}"), selections);

            assertEquals("", images.get("1"));
            assertEquals("TEXT_ONLY", selected.getStatus());
            assertTrue(selected.getGate().isPassed());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"timeout", "hash", "dimensions", "corrupt"})
    @DisplayName("SELECTED 下载、超时、哈希、尺寸或解码失败均显式退回纯文字并通过 assets 闸")
    void selectedAssetFailuresFallBackToTextOnly(String failure, @TempDir Path root) throws Exception {
        IMediaAssetService assets = mock(IMediaAssetService.class);
        MediaAssetSelectionVo selected = selection("SELECTED", "scene_001", 1);
        selected.setImageValue("https://oss.example.test/asset.png");
        selected.setContentSha256(DigestUtil.sha256Hex(PNG));
        selected.setWidth(1); selected.setHeight(1);
        when(assets.selectForScene(any())).thenReturn(selected);
        var fixture = new NewsProductionPipelineSceneReworkTest.Fixture(root, assets);
        NewsProductionPipeline pipeline = spy(fixture.pipeline);
        switch (failure) {
            case "timeout" -> doThrow(new SocketTimeoutException("timeout")).when(pipeline).downloadSelectedAsset(any());
            case "hash" -> doAnswer(invocation -> new byte[] {1, 2, 3}).when(pipeline).downloadSelectedAsset(any());
            case "dimensions" -> { selected.setWidth(2); doAnswer(invocation -> PNG).when(pipeline).downloadSelectedAsset(any()); }
            case "corrupt" -> doAnswer(invocation -> new byte[] {(byte) 0x89, 'P', 'N', 'G'}).when(pipeline).downloadSelectedAsset(any());
            default -> throw new AssertionError(failure);
        }
        List<MediaAssetSelectionVo> selections = new ArrayList<>();

        Map<String, String> images = pipeline.resolveSceneAssets(fixture.artifacts.prepareWorkDirectory(fixture.run),
            JSON.readTree("{\"scene_id\":\"scene_001\",\"scene_type\":\"image\"}"), 1,
            JSON.readTree("{}"), selections);

        assertEquals("", images.get("1"));
        assertEquals("", selected.getImageValue());
        assertEquals("TEXT_ONLY", selected.getStatus());
        assertEquals("asset_download_failed", selected.getReasonCode());
        assertTrue(selected.getGate().isPassed());
        assertEquals(true, selected.getGate().getMeasured().get("fallbackToText"));
        assertEquals(false, selected.getGate().getMeasured().get("assetPresent"));
    }

    @Test
    @DisplayName("TEXT_ONLY 固定空 imageValue，不下载 OSS")
    void textOnlyUsesEmptyImageValue(@TempDir Path root) throws Exception {
        IMediaAssetService assets = mock(IMediaAssetService.class);
        MediaAssetSelectionVo selected = selection("TEXT_ONLY", "scene_001", 1);
        when(assets.selectForScene(any())).thenReturn(selected);
        NewsProductionPipelineSceneReworkTest.Fixture fixture =
            new NewsProductionPipelineSceneReworkTest.Fixture(root, assets);
        List<MediaAssetSelectionVo> selections = new ArrayList<>();

        Map<String, String> images = fixture.pipeline.resolveSceneAssets(fixture.artifacts.prepareWorkDirectory(fixture.run),
            JSON.readTree("{\"scene_id\":\"scene_001\",\"scene_type\":\"image\"}"), 1,
            JSON.readTree("{}"), selections);

        assertEquals("", images.get("1"));
        assertEquals("", selected.getImageValue());
        assertTrue(selections.getFirst().getGate().isPassed());
    }

    @Test
    @DisplayName("返工只重新选择目标屏，未改屏继承已选素材且旧素材哈希保留在 asset manifest")
    void reworkReusesUnchangedSceneSelection(@TempDir Path root) throws Exception {
        IMediaAssetService assets = mock(IMediaAssetService.class);
        MediaAssetSelectionVo target = selection("TEXT_ONLY", "scene_001", 2);
        when(assets.selectForScene(any())).thenReturn(target);
        NewsProductionPipelineSceneReworkTest.Fixture fixture =
            new NewsProductionPipelineSceneReworkTest.Fixture(root, assets);
        ObjectNode previous = (ObjectNode) JSON.readTree(fixture.previousResultJson);
        ArrayNode selections = previous.putArray("mediaSelections");
        ObjectNode unchanged = (ObjectNode) JSON.readTree(JSON.writeValueAsString(selection("SELECTED", "scene_003", 1)));
        unchanged.put("imageValue", "scene3.png");
        selections.add(unchanged);
        selections.add(JSON.readTree(JSON.writeValueAsString(selection("SELECTED", "scene_001", 2))));
        ObjectNode replacement = (ObjectNode) fixture.request.replacementScene().deepCopy();
        replacement.put("scene_type", "image");
        replacement.putObject("visual").put("layout", "image_with_context")
            .putArray("assets").addObject().put("asset_id", "img_0").put("source_url", "https://example.com/photo.png");
        var request = new SceneReworkRequest("scene_001", replacement, 3L, fixture.previousManifestSha256,
            fixture.previousArtifacts, JSON.writeValueAsString(previous));

        ProductionResultVo result = fixture.pipeline.reworkScene(fixture.run, request);

        verify(assets).selectForScene(any(MediaAssetSelectionBo.class));
        JsonNode content = JSON.readTree(result.resultJson());
        JsonNode current = content.path("mediaSelections");
        assertEquals(2, current.size());
        assertEquals("scene_003", current.get(0).path("sceneId").asString());
        assertEquals("TEXT_ONLY", current.get(1).path("status").asString());
        assertEquals("", current.get(1).path("imageValue").asString());
        ProductionArtifactVo manifest = result.artifacts().stream()
            .filter(item -> "asset_manifest".equals(item.kind())).findFirst().orElseThrow();
        JsonNode files = JSON.readTree(Files.readString(root.resolve("data").resolve(manifest.storageKey()),
            StandardCharsets.UTF_8)).path("files");
        assertTrue(files.toString().contains("scene3.png"), "未改屏的已选素材描述必须继承");
        assertFalse(files.toString().contains("scene2.jpg"), "目标屏旧素材不能继续冒充新选择");
    }

    private static MediaAssetSelectionVo selection(String status, String sceneId, int number) {
        MediaAssetSelectionVo selected = new MediaAssetSelectionVo();
        selected.setSceneId(sceneId);
        selected.setSceneNumber(number);
        selected.setSceneType("IMAGE");
        selected.setStatus(status);
        selected.setReasonCode(status.equals("SELECTED") ? "selected" : "no_matching_asset");
        selected.setReason(status.equals("SELECTED") ? "已选择许可明确的素材" : "无匹配素材");
        selected.setImageVariable("scene" + number + "_image");
        selected.setImageValue(status.equals("SELECTED") ? "scene" + number + ".png" : "");
        selected.setAssetId(status.equals("SELECTED") ? 7L : null);
        selected.setGate(new MediaAssetGateVo("scene:" + sceneId, selected.getReasonCode(), selected.getReason(),
            true, selected.getAssetId(), !status.equals("SELECTED")));
        return selected;
    }
}
