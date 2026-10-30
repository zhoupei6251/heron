package org.dromara.heron.domain.vo;

/**
 * 内部产物描述，storageKey 不对外暴露。
 */
public record ProductionArtifactVo(String artifactId, String kind, String storageKey,
                                   long bytes, String sha256, String mediaType) {
}
