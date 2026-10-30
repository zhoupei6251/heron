package org.dromara.heron.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import org.dromara.heron.domain.HeronNewsWork;
import jakarta.validation.constraints.*;
import java.io.Serializable;

/** 以 expectedRevision 保存下一版草稿，不接收 owner 或任务指针。 */
@AutoMapper(target = HeronNewsWork.class, reverseConvertGenerate = false)
public record NewsDraftBo(@NotNull @Positive Long expectedRevision,
                          @NotBlank @Size(max = 200) String title,
                          @NotBlank @Size(max = 16000) String draft,
                          @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,80}") String templateKey,
                          @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,80}") String voiceKey) implements Serializable {
}
