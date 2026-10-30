package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.*;
import java.io.Serializable;

/** 人工审核必须绑定联络表与 MP4 两个完整 SHA-256。 */
public record NewsReviewBo(@NotBlank @Pattern(regexp = "approved|rejected") String decision,
                           @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contactSha256,
                           @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String videoSha256,
                           @Size(max = 1000) String reason) implements Serializable {
}
