package org.dromara.heron.config;

import org.dromara.heron.service.INewsSourceService;
import org.dromara.heron.service.impl.ApacheNewsTransport;
import org.dromara.heron.service.impl.DataOpportunityRunner;
import org.dromara.heron.service.impl.DataOpportunityService;
import org.dromara.heron.service.impl.NewsAutoRunner;
import org.dromara.heron.service.impl.NewsAutoService;
import org.dromara.heron.service.impl.NewsCleanupRunner;
import org.dromara.heron.service.impl.NewsSourceRunner;
import org.dromara.heron.service.impl.NewsWorkServiceImpl;
import org.dromara.heron.service.impl.TrendCollectRunner;
import org.dromara.heron.service.impl.TrendCollectService;
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

    /** 自动产出只在部署显式打开时装配；自动发布走库存择优，不再旁路直发。 */
    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "auto-enabled", havingValue = "true")
    public NewsAutoRunner newsAutoRunner(NewsAutoService auto, HeronNewsProperties news,
                                         org.dromara.heron.service.impl.PublishScheduler publisher,
                                         @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        return new NewsAutoRunner(auto, news, publisher, scheduler);
    }

    /** 数据机会分析同样只在显式打开时装配：第一步不接入生产，默认不跑。 */
    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "data-analysis-enabled", havingValue = "true")
    public DataOpportunityRunner dataOpportunityRunner(DataOpportunityService opportunities, HeronNewsProperties news,
                                                       @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        return new DataOpportunityRunner(opportunities, news, scheduler);
    }

    /** 成片本地副本保留 15 天，到期定时清理；清理自带发布保护与 .keep，无需额外开关。 */
    @Bean
    public NewsCleanupRunner newsCleanupRunner(NewsWorkServiceImpl maintenance,
                                               @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        return new NewsCleanupRunner(maintenance, scheduler);
    }

    /** 抖音热点采集只在显式打开时装配：默认不出网、不占浏览器。 */
    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "trend-enabled", havingValue = "true")
    public TrendCollectRunner trendCollectRunner(TrendCollectService collect, HeronNewsProperties news,
                                                 @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        return new TrendCollectRunner(collect, news, scheduler);
    }
}
