package org.dromara.heron.service.impl;

import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("原型清单媒体类型契约")
class ProtoManifestMediaTest {

    @Test
    @DisplayName("rp1 产物按 RP/1 的 DSL 文本判定，拒绝 JSON")
    void rp1RequiresPlainText() {
        assertTrue(ProtoProductionDomain.mediaMatches(artifact("rp1", "text/plain")));
        assertFalse(ProtoProductionDomain.mediaMatches(artifact("rp1", "application/json")));
    }

    private static ProductionArtifactVo artifact(String kind, String mediaType) {
        return new ProductionArtifactVo("00000000-0000-0000-0000-000000000000", kind,
            "protos/1/v1/a1-00000000-0000-0000-0000-000000000000/source." + kind, 1L, "a".repeat(64), mediaType);
    }
}
