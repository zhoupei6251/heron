package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/** 人工授权或限制参考素材；任何决定都必须留理由。 */
public record ReferenceAssetAuthorizeBo(@NotNull Boolean allowed,
                                        @NotBlank @Size(max = 1000) String reason) implements Serializable {
}
