package org.dromara.heron.domain.vo;

/** 内部保留期维护结果；失败可重试，不把开始清理当作已清理。 */
public record NewsCleanupVo(Long versionId, boolean cleaned, String errorCode) {
}
