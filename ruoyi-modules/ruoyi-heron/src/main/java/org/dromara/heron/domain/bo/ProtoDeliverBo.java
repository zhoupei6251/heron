package org.dromara.heron.domain.bo;

/** 用户确认后的交付命令，不接受客户端提供交付人/时间/原生验证结果。 */
public record ProtoDeliverBo(String requestKey, String rpSha256, String previewSha256) { }
