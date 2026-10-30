package org.dromara.heron.domain.vo;

/**
 * 业务 domain 预留的不可变版本与冻结输入。
 */
public record ProductionReservationVo(long versionId, long ownerId, String inputJson) {
}
