package org.dromara.heron.config;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.impl.FrozenContextService;
import org.dromara.heron.service.impl.NewsProductionPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@DisplayName("新闻流水线配置接线")
class HeronProductionConfigurationTest {

    @Test
    @DisplayName("部署配置对象必须传进流水线，不能新建默认关实例")
    void passesNewsPropertiesIntoPipeline() throws Exception {
        HeronNewsProperties newsProperties = new HeronNewsProperties();
        newsProperties.getSubtitles().setBurn(true);

        NewsProductionPipeline pipeline = new HeronProductionConfiguration().videoDelivery(
            null, null, null, null, new HeronProductionProperties(), mock(IProductionExecutionService.class),
            null, null, null, null, null, null, null, null, newsProperties, null, null, null, null);

        Field field = NewsProductionPipeline.class.getDeclaredField("newsProperties");
        field.setAccessible(true);
        assertSame(newsProperties, field.get(pipeline));
    }

    @Test
    void eachContentExecutorRejectsAnotherLanesSnapshot() {
        var config = new HeronProductionConfiguration();
        var delivery = config.videoDelivery(null, null, null, null, new HeronProductionProperties(),
            mock(IProductionExecutionService.class), null, null, null, null, null, null,
            mock(FrozenContextService.class), null, new HeronNewsProperties(), null, null, null, null);
        Map<String, IProductionPipeline> entries = Map.of(
            "newsExplainerPipeline", config.newsExplainerPipeline(delivery),
            "dataBoardPipeline", config.dataBoardPipeline(delivery),
            "trendInsightPipeline", config.trendInsightPipeline(delivery),
            "knowledgeExplainerPipeline", config.knowledgeExplainerPipeline(delivery));
        for (var line : ProductionBizType.videoLanes()) {
            var wrong = line == ProductionBizType.news_explainer ? ProductionBizType.data_board : ProductionBizType.news_explainer;
            var run = new ProductionRunVo(1, line, 2, 3, 1, "test", "{\"kind\":\"" + wrong.kind() + "\"}");
            var failure = assertThrows(HeronBusinessException.class, () -> entries.get(line.pipelineBean()).execute(run));
            assertEquals("news_line_conflict", failure.getCode());
        }
    }
}
