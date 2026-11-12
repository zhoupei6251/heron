package org.dromara.heron.support;

import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.service.impl.NewsCandidateCollector;
import org.dromara.heron.service.impl.NewsProductionDomain;
import org.dromara.heron.service.impl.NewsSourceFetcher;
import org.dromara.heron.service.impl.NewsSourceServiceImpl;
import org.dromara.heron.service.impl.NewsWorkServiceImpl;
import org.dromara.heron.service.impl.SandboxPublishAdapter;
import org.dromara.heron.service.impl.NewsTemplatePicker;
import org.dromara.heron.service.impl.NewsMetricServiceImpl;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/** N1 独立装配：复用真实 MySQL 设施，按名字替换测试域，不用 @Primary 掩盖同名域。 */
@TestConfiguration
@org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
@Import({HeronMysqlTestConfiguration.class, NewsWorkServiceImpl.class, NewsSourceServiceImpl.class,
    NewsSourceFetcher.class, NewsCandidateCollector.class, SandboxPublishAdapter.class,
    NewsTemplatePicker.class, NewsMetricServiceImpl.class, SpringUtils.class,
    io.github.linpeilie.mapstruct.MapstructAutoConfiguration.class})
public class NewsMysqlTestConfiguration {
    @Bean
    static BeanFactoryPostProcessor newsDomainBinding() {
        return factory -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
            registry.removeBeanDefinition("newsProductionDomain");
            registry.removeBeanDefinition("newsProductionPipeline");
            RootBeanDefinition definition = new RootBeanDefinition(NewsProductionDomain.class);
            definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            registry.registerBeanDefinition("newsProductionDomain", definition);
        };
    }

    @Bean
    JsonMapper newsJsonMapper() {
        return JsonMapper.builder().build();
    }

    /** 采集夹具同时充当传输与解析器：来源采集只打到这里，不出网。 */
    @Bean
    HeronNewsTestFixtures heronNewsTestFixtures() {
        return new HeronNewsTestFixtures();
    }

    @Bean
    HeronNewsProperties heronNewsProperties() {
        HeronNewsProperties properties = new HeronNewsProperties();
        properties.setAllowedHosts(new ArrayList<>(List.of("feed.example.test", "other.example.test",
            "feed1.example.test", "feed2.example.test", "feed3.example.test", "feed4.example.test",
            "feed5.example.test", "feed6.example.test", "feed7.example.test")));
        return properties;
    }
}
