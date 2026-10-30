package org.dromara.heron.domain.vo;

import java.util.List;

/**
 * 流水线成功产物，不包含审核或交付决定。
 */
public record ProductionResultVo(List<ProductionArtifactVo> artifacts, String resultJson) {
}
