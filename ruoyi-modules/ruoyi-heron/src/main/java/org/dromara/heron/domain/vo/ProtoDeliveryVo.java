package org.dromara.heron.domain.vo;

import java.time.Instant;

/** 交付时刻事实；后补原生验证不得改变 nativeVerifiedAtDelivery。 */
public record ProtoDeliveryVo(long actorId, Instant deliveredAt, String rpSha256, String previewSha256,
                              boolean nativeVerifiedAtDelivery, String verificationId) { }
