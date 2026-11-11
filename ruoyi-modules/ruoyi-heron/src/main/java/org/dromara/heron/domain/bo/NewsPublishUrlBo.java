package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/** 补回作品链接：命令行发布不回传链接，人从抖音后台复制一次填进来。 */
public record NewsPublishUrlBo(@NotBlank @Size(max = 2048) String workUrl) implements Serializable {
}
