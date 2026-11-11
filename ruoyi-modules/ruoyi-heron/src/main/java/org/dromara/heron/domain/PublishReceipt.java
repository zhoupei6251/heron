package org.dromara.heron.domain;

import java.time.LocalDateTime;

/**
 * 发布回执：source 决定这条记录能不能被当成「已上平台」的证据。
 * sandbox 只证明链路通了，不证明内容已发布。
 */
public record PublishReceipt(String workUrl, String account, LocalDateTime publishedAt, String source) {
}
