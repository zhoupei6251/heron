package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.service.impl.shared.production.NewsProductionDomain;
import org.dromara.heron.service.impl.shared.production.ProductionArtifactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewsPreviewFileTest {
    @TempDir Path root;

    @Test
    void opensVerifiedSubtitleBytesWithJsonNameAndType() throws Exception {
        var artifacts = mock(ProductionArtifactService.class);
        var properties = new HeronProductionProperties();
        properties.setDataRoot(root);
        var domain = new NewsProductionDomain(null, null, null, null, null, null, null, artifacts, properties, null);
        var version = new HeronVideoVersion();
        version.setWorkId(1L);
        version.setVersionNo(2);
        var file = new ProductionArtifactVo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "subtitles",
            "videos/1/v2/a1-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.json",
            2, "b".repeat(64), "application/json");
        when(artifacts.openVerified(file)).thenReturn(new ByteArrayInputStream("[]".getBytes()));
        try (var opened = domain.openFile(version, file)) {
            assertEquals("news-1-v2-subtitles.json", opened.fileName());
            assertEquals("application/json", opened.mediaType());
            assertArrayEquals("[]".getBytes(), opened.content().readAllBytes());
        }
        verify(artifacts).openVerified(file);
    }

    @Test
    void previewPolicyKeepsInternalArtifactsPrivate() {
        assertEquals(".mp4", NewsProductionDomain.requirePreviewKind("mp4"));
        assertEquals("-contact.png", NewsProductionDomain.requirePreviewKind("contact_sheet"));
        assertEquals("-subtitles.json", NewsProductionDomain.requirePreviewKind("subtitles"));
        for (String kind : new String[] { "script", "checks", "asset_manifest", "dataset", "../subtitles" }) {
            assertThrows(HeronAccessException.class, () -> NewsProductionDomain.requirePreviewKind(kind));
        }
    }
}
