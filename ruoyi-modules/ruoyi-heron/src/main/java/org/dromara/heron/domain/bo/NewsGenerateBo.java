package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.*;
import java.io.Serializable;

/** SP1 持久幂等请求，不携带可变草稿内容。 */
public record NewsGenerateBo(@NotBlank @Pattern(regexp = "[a-zA-Z0-9._:-]{1,64}") String requestKey,
                             @NotNull @Positive Long expectedRevision) implements Serializable {
}
