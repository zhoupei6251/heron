package org.dromara.heron.service.impl;

import org.dromara.heron.agentscope.TrendLearnTools;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.dromara.heron.mapper.TrendLearnOssMapper;
import org.dromara.heron.service.impl.shared.media.ReferenceVideoDownloader;
import org.dromara.heron.service.impl.shared.research.TrendLearnDocuments;
import org.dromara.heron.service.impl.shared.research.TrendLearnService;
import org.dromara.heron.service.impl.shared.research.TrendLearnStorage;
import org.dromara.heron.service.impl.shared.research.TrendLearningAgent;
import org.dromara.heron.service.impl.shared.research.TrendVideoDecomposer;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TrendLearnWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TrendLearnService.class, TrendLearningAgent.class, TrendLearnStorage.class,
            TrendVideoDecomposer.class, TrendLearnDocuments.class)
        .withBean(HeronTrendVideoMapper.class, () -> mock(HeronTrendVideoMapper.class))
        .withBean(HeronAgentToolCallMapper.class, () -> mock(HeronAgentToolCallMapper.class))
        .withBean(TrendLearnOssMapper.class, () -> mock(TrendLearnOssMapper.class))
        .withBean(OpenAiModelClient.class, () -> mock(OpenAiModelClient.class))
        .withBean(ReferenceVideoDownloader.class, () -> mock(ReferenceVideoDownloader.class))
        .withBean(OssService.class, () -> mock(OssService.class))
        .withBean(HeronNewsProperties.class, HeronNewsProperties::new)
        .withBean(HeronProductionProperties.class, HeronProductionProperties::new)
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void learningServicesWireWithoutTurningPerVideoToolsIntoBean() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TrendLearnService.class)
                .hasSingleBean(TrendLearningAgent.class)
                .hasSingleBean(TrendLearnStorage.class)
                .hasSingleBean(TrendVideoDecomposer.class)
                .hasSingleBean(TrendLearnDocuments.class)
                .doesNotHaveBean(TrendLearnTools.class);
        });
    }
}
