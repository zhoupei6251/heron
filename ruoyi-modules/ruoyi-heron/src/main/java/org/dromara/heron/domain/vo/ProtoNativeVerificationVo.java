package org.dromara.heron.domain.vo;

import java.time.Instant;

/** 用户声明的验证事实，不等同于引擎自动验证。 */
public record ProtoNativeVerificationVo(String verificationId, String requestKey, long actorId,
                                        Instant verifiedAt, String rpSha256, String targetVersion,
                                        boolean passed, String note) { }
