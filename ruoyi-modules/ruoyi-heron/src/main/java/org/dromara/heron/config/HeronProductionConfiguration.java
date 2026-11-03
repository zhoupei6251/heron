package org.dromara.heron.config;

import org.dromara.common.core.factory.YmlPropertySourceFactory;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.ProtoRp1Author;
import org.dromara.heron.service.impl.ProtoProductionPipeline;
import org.dromara.heron.service.impl.ProductionArtifactService;
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

    /**
     * 装配流水线需要 RP/1 作者 bean 同时存在；只开开关不配模型时启动即失败，不留到运行期才报 503。
     */
    @Bean("prototypeProductionPipeline")
    @ConditionalOnProperty(prefix = "heron.prototype", name = "engine-enabled", havingValue = "true")
    public ProtoProductionPipeline prototypeProductionPipeline(ProtoRp1Author author,
                                                              ProductionProcessService processes,
                                                              ProductionArtifactService artifacts,
                                                              HeronProductionProperties properties) {
        return new ProtoProductionPipeline(author, processes, artifacts, properties);
    }
}
