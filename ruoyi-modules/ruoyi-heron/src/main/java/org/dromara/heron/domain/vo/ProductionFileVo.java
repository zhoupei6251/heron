package org.dromara.heron.domain.vo;

/**
 * 对外文件描述，不含存储路径。
 */
public record ProductionFileVo(String artifactId, String kind, long bytes,
                               String sha256, String mediaType, boolean available) {
}
