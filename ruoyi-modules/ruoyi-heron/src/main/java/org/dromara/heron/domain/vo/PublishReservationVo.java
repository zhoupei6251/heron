package org.dromara.heron.domain.vo;

import java.io.Serializable;

/** 一次人工预占的结果：预占成功后交由既有发布链提交。 */
public record PublishReservationVo(long inventoryId, long workId, long versionId, String bizLine)
    implements Serializable {
}
