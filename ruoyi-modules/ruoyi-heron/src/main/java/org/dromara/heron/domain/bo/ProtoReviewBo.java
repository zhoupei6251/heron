package org.dromara.heron.domain.bo;

/** 审核当前文件哈希；decision 仅 approved/rejected，驳回必须说明理由。 */
public record ProtoReviewBo(String decision, String rpSha256, String previewSha256, String reason) { }
