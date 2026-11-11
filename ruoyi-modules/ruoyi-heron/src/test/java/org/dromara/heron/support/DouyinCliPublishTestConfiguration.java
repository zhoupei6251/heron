package org.dromara.heron.support;

import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.service.IPublishAdapter;
import org.dromara.heron.service.impl.DouyinCliPublishAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.temporal.ChronoUnit;

/**
 * 把发布出口换成命令行回执的替身：命令真的跑不了（要登录、要联网），
 * 但「回执不带链接」这条事实必须按真实形状进库，补链接规则才有东西可验。
 * 沙箱适配器仍在上下文里，只在别的测试用；这条测试按类型注入时优先拿替身。
 */
@TestConfiguration
public class DouyinCliPublishTestConfiguration {

    @Bean
    @Primary
    IPublishAdapter douyinCliPublishAdapter() {
        return new DouyinReceiptStub();
    }

    public static class DouyinReceiptStub implements IPublishAdapter {
        @Override
        public String source() {
            return DouyinCliPublishAdapter.SOURCE;
        }

        @Override
        public PublishReceipt publish(PublishRequest request) {
            return new PublishReceipt(null, "heron", request.requestedAt().truncatedTo(ChronoUnit.MILLIS), source());
        }
    }
}
