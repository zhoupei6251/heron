package org.dromara.heron.controller;

import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.domain.vo.MediaAssetVo;
import org.dromara.heron.service.IMediaAssetService;
import org.dromara.heron.support.MediaAssetTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("素材 API：多段入库与场景选材路由")
class MediaAssetControllerTest {

    private final IMediaAssetService service = mock(IMediaAssetService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MediaAssetTestSupport.initialize();
        mvc = MockMvcBuilders.standaloneSetup(new MediaAssetController(service))
            .setControllerAdvice(new HeronExceptionHandler()).build();
    }

    @Test
    void selectionReturnsTextOnlyContract() throws Exception {
        MediaAssetSelectionVo result = new MediaAssetSelectionVo();
        result.setSceneId("scene-2");
        result.setSceneNumber(2);
        result.setStatus("TEXT_ONLY");
        result.setImageVariable("scene2_image");
        result.setImageValue("");
        when(service.selectForScene(any())).thenReturn(result);

        mvc.perform(post("/heron/mediaAsset/select").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sceneId\":\"scene-2\",\"sceneNumber\":2,\"sceneType\":\"image\",\"assetId\":7}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("TEXT_ONLY"))
            .andExpect(jsonPath("$.data.imageVariable").value("scene2_image"))
            .andExpect(jsonPath("$.data.imageValue").value(""));

        ArgumentCaptor<MediaAssetSelectionBo> request = ArgumentCaptor.forClass(MediaAssetSelectionBo.class);
        verify(service).selectForScene(request.capture());
        assertEquals(7L, request.getValue().assetId());
    }

    @Test
    void selectionAcceptsMaximumSceneId() throws Exception {
        String sceneId = "a".repeat(250);
        MediaAssetSelectionVo result = new MediaAssetSelectionVo();
        result.setSceneId(sceneId);
        when(service.selectForScene(any())).thenReturn(result);

        mvc.perform(post("/heron/mediaAsset/select").contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJsonString(MediaAssetSelectionBo.builder()
                    .sceneId(sceneId).sceneNumber(2).sceneType("image").build())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sceneId").value(sceneId));

        ArgumentCaptor<MediaAssetSelectionBo> request = ArgumentCaptor.forClass(MediaAssetSelectionBo.class);
        verify(service).selectForScene(request.capture());
        assertEquals(sceneId, request.getValue().sceneId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSceneIds")
    void selectionRejectsInvalidSceneIdBeforeService(String label, String sceneId) throws Exception {
        MediaAssetSelectionBo request = MediaAssetSelectionBo.builder()
            .sceneId(sceneId).sceneNumber(2).sceneType("image").assetId(7L).build();

        mvc.perform(post("/heron/mediaAsset/select").contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJsonString(request)))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
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
    void selectionSurfacesLicenseRejectionAsConflict() throws Exception {
        when(service.selectForScene(any())).thenThrow(new HeronBusinessException(409,
            "media_asset_license_rejected", "素材许可未知，拒绝进入生产"));

        mvc.perform(post("/heron/mediaAsset/select").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sceneId\":\"scene-2\",\"sceneNumber\":2,\"sceneType\":\"image\",\"assetId\":7}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.msg").value("素材许可未知，拒绝进入生产"));
    }

    @Test
    void multipartIngestionBindsMetadataAndFileSeparately() throws Exception {
        MediaAssetVo vo = new MediaAssetVo();
        vo.setId(9L);
        when(service.insertByBo(any(), any())).thenReturn(vo);
        String metadata = """
            {"name":"城市","sourceUri":"https://source.example.test/city",
             "licenseCode":"CC_BY","licenseEvidenceUri":"https://license.example.test/cc-by","tags":"城市"}
            """;

        mvc.perform(multipart("/heron/mediaAsset")
                .file(new MockMultipartFile("file", "city.png", "image/png", new byte[] {1, 2, 3}))
                .part(new MockPart("metadata", null, metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MediaType.APPLICATION_JSON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(9));

        ArgumentCaptor<org.dromara.heron.domain.bo.MediaAssetBo> bo =
            ArgumentCaptor.forClass(org.dromara.heron.domain.bo.MediaAssetBo.class);
        verify(service).insertByBo(bo.capture(), any());
        assertEquals("城市", bo.getValue().getName());
        assertEquals("CC_BY", bo.getValue().getLicenseCode());
    }

    @Test
    void invalidMetadataIsRejectedBeforeService() throws Exception {
        String metadata = """
            {"name":"","sourceUri":"https://source.example.test/city",
             "licenseCode":"CC_BY","licenseEvidenceUri":"https://license.example.test/cc-by"}
            """;

        mvc.perform(multipart("/heron/mediaAsset")
                .file(new MockMultipartFile("file", "city.png", "image/png", new byte[] {1, 2, 3}))
                .part(new MockPart("metadata", null, metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MediaType.APPLICATION_JSON)))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
