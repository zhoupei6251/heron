package org.dromara.heron.domain.bo;

/** 人工原生验证声明。五个动作只能来自用户显式输入，不能由任务成功推导。 */
public record ProtoNativeVerificationBo(String requestKey, String rpSha256, String targetVersion,
                                        boolean opened, boolean edited, boolean saved, boolean reopened,
                                        boolean navigationChecked, String note) { }
