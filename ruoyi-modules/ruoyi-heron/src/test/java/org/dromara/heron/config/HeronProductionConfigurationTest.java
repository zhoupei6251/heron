package org.dromara.heron.config;

import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.impl.NewsProductionPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

@DisplayName("新闻流水线配置接线")
class HeronProductionConfigurationTest {

    @Test
    @DisplayName("部署配置对象必须传进流水线，不能新建默认关实例")
    void passesNewsPropertiesIntoPipeline() throws Exception {
        HeronNewsProperties newsProperties = new HeronNewsProperties();
        newsProperties.getSubtitles().setBurn(true);

        NewsProductionPipeline pipeline = new HeronProductionConfiguration().newsProductionPipeline(
            null, null, null, null, new HeronProductionProperties(), mock(IProductionExecutionService.class),
            null, null, null, null, null, null, null, null, newsProperties);

        Field field = NewsProductionPipeline.class.getDeclaredField("newsProperties");
        field.setAccessible(true);
        assertSame(newsProperties, field.get(pipeline));
    }
}
