package org.dromara.heron.domain.bo;

import java.io.Serializable;

/** 核对必须绑定具体尝试和视频；确认已发布时提供平台回执，确认未发布时先停止上传。 */
public record NewsPublishResolveBo(String decision, String videoSha256, String reason,
                                   boolean uploadStopped, NewsPublishBo receipt) implements Serializable {
}
