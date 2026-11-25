package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.io.Serializable;
import java.util.List;

/** 场景选材需求；sceneNumber 用于映射既有 sceneN_image 模板变量。 */
@Builder
public record MediaAssetSelectionBo(
    @NotBlank(message = "场景 ID 不能为空")
    @Pattern(regexp = MediaAssetSelectionBo.SCENE_ID_PATTERN, message = "场景 ID 仅允许 1-250 个 ASCII 字母、数字或 _ . : # [ ] -")
    String sceneId,
    @NotNull(message = "场景序号不能为空") @Min(value = 1, message = "场景序号必须大于 0")
    @Max(value = 99, message = "场景序号不能超过 99") Integer sceneNumber,
    @NotBlank(message = "场景类型不能为空") String sceneType,
    Long assetId,
    String orientation,
    @Size(max = 20, message = "场景关键词不能超过 20 个") List<String> keywords,
    String sourceUrl
) implements Serializable {

    /** 250 + Gate 的 scene: 前缀 6 字符，合计不超过现有 checks 的 256 字符上限。 */
    public static final String SCENE_ID_PATTERN = "[a-zA-Z0-9_.:#\\[\\]-]{1,250}";

    public MediaAssetSelectionBo(String sceneId, Integer sceneNumber, String sceneType, Long assetId,
                                 String orientation, List<String> keywords) {
        this(sceneId, sceneNumber, sceneType, assetId, orientation, keywords, null);
    }

    public MediaAssetSelectionBo {
        keywords = keywords == null ? List.of() : keywords.stream()
            .filter(item -> item != null && !item.isBlank()).toList();
    }
}
