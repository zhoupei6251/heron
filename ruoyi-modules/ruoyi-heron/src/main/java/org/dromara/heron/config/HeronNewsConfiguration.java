package org.dromara.heron.config;

import org.dromara.heron.service.INewsSourceService;
import org.dromara.heron.service.impl.ApacheNewsTransport;
import org.dromara.heron.service.impl.NewsSourceRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;

/**
 * 新闻来源采集装配。allowed-hosts 与开关只来自部署配置，默认空清单意味着没有任何来源可出网。
 */
@Configuration
@EnableConfigurationProperties(HeronNewsProperties.class)
public class HeronNewsConfiguration {

    @Bean
    public ApacheNewsTransport apacheNewsTransport() {
        return new ApacheNewsTransport();
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "collection-enabled", havingValue = "true")
    public NewsSourceRunner newsSourceRunner(INewsSourceService sources,
                                            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        return new NewsSourceRunner(sources, scheduler);
    }
}
