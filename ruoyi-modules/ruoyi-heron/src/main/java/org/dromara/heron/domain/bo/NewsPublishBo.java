package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 人工发布回执：链接、账号、发布时间必须绑定被发布成片的完整 SHA-256。 */
public record NewsPublishBo(@NotBlank @Size(max = 2048) String workUrl,
                            @NotBlank @Size(max = 120) String account,
                            @NotNull LocalDateTime publishedAt,
                            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String videoSha256) implements Serializable {
}
