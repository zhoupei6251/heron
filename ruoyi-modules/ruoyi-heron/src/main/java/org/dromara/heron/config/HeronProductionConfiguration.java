package org.dromara.heron.config;

import org.dromara.common.core.factory.YmlPropertySourceFactory;
import org.dromara.heron.agentscope.DirectorRepairAgent;
import org.dromara.heron.agentscope.ShotDirectorAgent;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.IMediaAssetService;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.dromara.heron.service.impl.DatasetRouter;
import org.dromara.heron.service.impl.DatasetScriptAuthor;
import org.dromara.heron.service.impl.KnowledgeScriptAuthor;
import org.dromara.heron.service.impl.ModelPriceConfig;
import org.dromara.heron.service.impl.NewsSourceFetcher;
import org.dromara.heron.service.impl.EditorialReviewer;
import org.dromara.heron.service.impl.FrozenContextService;
import org.dromara.heron.service.impl.NewsProductionPipeline;
import org.dromara.heron.service.impl.ProductionArtifactCleanupRunner;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.dromara.heron.service.impl.ProductionRecoveryService;
import org.dromara.heron.service.impl.ProductionRunner;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.dromara.heron.service.impl.QualityReviewer;
import org.dromara.system.api.ConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

import java.util.concurrent.ScheduledExecutorService;

/**
 * 模块内配置入口，不改动基座 application 配置。
 */
@Configuration
@EnableConfigurationProperties(HeronProductionProperties.class)
@PropertySource(value = "classpath:heron-production.yml", factory = YmlPropertySourceFactory.class)
public class HeronProductionConfiguration {

    /**
     * 单价与单次 run 上限的唯一读入口：成本概览（读侧折算）与执行闸（写侧上限）共用一份，
     * 免得两处各读一遍 sys_config 后口径漂移。
     */
    @Bean
    public ModelPriceConfig modelPriceConfig(ConfigService config, Environment environment) {
        return ModelPriceConfig.of(config, environment);
    }

    @Bean
    public ProductionArtifactCleanupRunner productionArtifactCleanupRunner(ProductionArtifactService artifacts,
                                                                             @Qualifier("scheduledExecutorService")
                                                                             ScheduledExecutorService scheduler) {
        return new ProductionArtifactCleanupRunner(artifacts, scheduler);
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
    public NewsProductionPipeline videoDelivery(@Qualifier("newsModelScriptAuthor") NewsScriptAuthor author,
                                                         KnowledgeScriptAuthor knowledgeAuthor,
                                                         ProductionProcessService processes,
                                                         ProductionArtifactService artifacts,
                                                         HeronProductionProperties properties,
                                                         IProductionExecutionService execution,
                                                         DatasetRouter datasetRouter,
                                                         DatasetFetchService datasetFetch,
                                                         DatasetScriptAuthor datasetAuthor,
                                                         EditorialReviewer reviewer,
                                                         QualityReviewer qualityReviewer,
                                                         NewsSourceFetcher newsSourceFetcher,
                                                         FrozenContextService frozenContexts,
                                                         IMediaAssetService mediaAssets,
                                                         HeronNewsProperties newsProperties,
                                                         DirectorRepairAgent repairAgent,
                                                         ShotDirectorAgent shotDirector,
                                                         org.dromara.heron.service.impl.TreatmentDirector treatmentDirector,
                                                         org.dromara.heron.service.impl.BeatAnalysis beatAnalysis) {
        return new NewsProductionPipeline(author, knowledgeAuthor, processes, artifacts, properties,
            execution::beginStage, datasetRouter, datasetFetch, datasetAuthor, reviewer, qualityReviewer,
            newsSourceFetcher, frozenContexts, mediaAssets, newsProperties, repairAgent, shotDirector, treatmentDirector,
            beatAnalysis);
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
    public IProductionPipeline newsExplainerPipeline(NewsProductionPipeline delivery) {
        return delivery.newsExplainer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
    public IProductionPipeline dataBoardPipeline(NewsProductionPipeline delivery) {
        return delivery.dataBoard();
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
    public IProductionPipeline trendInsightPipeline(NewsProductionPipeline delivery) {
        return delivery.trendInsight();
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
    public IProductionPipeline knowledgeExplainerPipeline(NewsProductionPipeline delivery) {
        return delivery.knowledgeExplainer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "heron.production", name = "worker-enabled", havingValue = "true")
    public ProductionRunner productionRunner(ApplicationContext pipelines,
                                             IProductionExecutionService execution,
                                             ProductionProcessService processes,
                                             ProductionRecoveryService recovery,
                                             ProductionUsageService usage,
                                             ProductionArtifactService artifacts,
                                             @Qualifier("scheduledExecutorService")
                                             ScheduledExecutorService scheduler) {
        return new ProductionRunner(pipelines, execution, processes, recovery, usage, artifacts, scheduler);
    }
}
