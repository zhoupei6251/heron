package org.dromara.heron.service.impl;

import org.dromara.heron.domain.vo.ProductionArtifactVo;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 单屏返工输入：只替换一个 scene_id 的画面，口播、字幕与既有音轨保持不变。
 *
 * @param sceneId                 要重渲染的稳定场景标识
 * @param replacementScene        完整场景对象；lead/body 必须与原稿一致
 * @param previousVersionId       上一版版本 ID，用于绑定产物归属
 * @param expectedManifestSha256  上一版完整产物清单的规范化 JSON SHA-256
 * @param previousArtifacts       上一版完整产物清单，必须与预期指纹一致
 * @param previousResultJson      上一版 result JSON；sourceVersionId 与 artifactManifestSha256 必须一致
 */
public record SceneReworkRequest(String sceneId, JsonNode replacementScene, Long previousVersionId,
                                 String expectedManifestSha256, List<ProductionArtifactVo> previousArtifacts,
                                 String previousResultJson) {

    public SceneReworkRequest {
        previousArtifacts = previousArtifacts == null ? List.of() : List.copyOf(previousArtifacts);
    }
}
