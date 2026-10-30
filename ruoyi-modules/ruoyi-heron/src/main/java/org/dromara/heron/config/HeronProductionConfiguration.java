package org.dromara.heron.config;

import org.dromara.common.core.factory.YmlPropertySourceFactory;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.dromara.heron.service.impl.ProductionRecoveryService;
import org.dromara.heron.service.impl.ProductionRunner;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.util.concurrent.ScheduledExecutorService;

/**
 * 模块内配置入口，不改动基座 application 配置。
 */
@Configuration
@EnableConfigurationProperties(HeronProductionProperties.class)
@PropertySource(value = "classpath:heron-production.yml", factory = YmlPropertySourceFactory.class)
public class HeronProductionConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "heron.production", name = "worker-enabled", havingValue = "true")
    public ProductionRunner productionRunner(ApplicationContext pipelines,
                                             IProductionExecutionService execution,
                                             ProductionProcessService processes,
                                             ProductionRecoveryService recovery,
                                             ProductionUsageService usage,
                                             @Qualifier("scheduledExecutorService")
                                             ScheduledExecutorService scheduler) {
        return new ProductionRunner(pipelines, execution, processes, recovery, usage, scheduler);
    }
}
