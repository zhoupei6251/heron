package org.dromara.heron.domain;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 发布请求：只带发布必需的标识，适配器自己决定怎么和平台交互。
 * videoFile 是成片的本地绝对路径，命令行出口要它；对象存储模式下为 null，沙箱出口不用。
 * requestedAt 用调用方的时钟，避免适配器与业务层各用一套时间导致回执被判成「未来时间」。
 */
public record PublishRequest(long taskId, long versionId, String title, String videoSha256, LocalDateTime requestedAt,
                             Path videoFile) {
}
