package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 发布表现回填：平台侧数据由人工录入，或将来由开放平台接口拉取。 */
public record NewsMetricBo(@NotNull Long versionId,
                           @NotBlank @Size(max = 32) String platform,
                           @Min(0) Long plays,
                           @DecimalMin("0") @DecimalMax("1") BigDecimal completionRate,
                           @Min(0) Long likes,
                           @Min(0) Long comments,
                           @Min(0) Long shares,
                           LocalDateTime fetchedAt) implements Serializable {
}
