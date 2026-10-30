package org.dromara.heron.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.heron.domain.HeronNewsSource;
import java.io.Serializable;

/** 来源维护输入，不允许客户端覆盖抓取租约和错误事实。 */
@Data
@AutoMapper(target = HeronNewsSource.class, reverseConvertGenerate = false)
public class NewsSourceBo implements Serializable {
    @NotNull(groups = EditGroup.class) private Long sourceId;
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Pattern(regexp = "rss|atom|text") private String sourceType;
    @NotBlank @Size(max = 2048) private String url;
    @NotNull @Min(60) private Integer intervalSeconds;
    @NotBlank @Pattern(regexp = "0|1") private String enabled;
}
