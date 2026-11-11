package org.dromara.heron.service.impl;

import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.service.IPublishAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;

/**
 * 沙箱发布出口：凭据到位前用它把「确认 → 发布 → 回执」整条链路跑通。
 * 回执的 URL 用 sandbox:// 前缀，页面据此显示「未上抖音」；绝不伪装成抖音链接。
 * 未配置 heron.news.publish-adapter 时的默认出口。
 */
@Service
@ConditionalOnProperty(prefix = "heron.news", name = "publish-adapter", havingValue = SandboxPublishAdapter.SOURCE,
    matchIfMissing = true)
public class SandboxPublishAdapter implements IPublishAdapter {
    public static final String SOURCE = "sandbox";
    static final String ACCOUNT = "sandbox";
    static final String URL_PREFIX = "sandbox://news/";

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public PublishReceipt publish(PublishRequest request) {
        return new PublishReceipt(URL_PREFIX + request.versionId(), ACCOUNT,
            request.requestedAt().truncatedTo(ChronoUnit.MILLIS), SOURCE);
    }
}
