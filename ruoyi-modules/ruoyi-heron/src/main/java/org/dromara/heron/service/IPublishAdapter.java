package org.dromara.heron.service;

import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;

/** 发布出口。失败必须抛异常并带原因，不返回半份回执。 */
public interface IPublishAdapter {
    /** 回执来源标识，落 heron_video_version.publish_source。 */
    String source();

    PublishReceipt publish(PublishRequest request);
}
